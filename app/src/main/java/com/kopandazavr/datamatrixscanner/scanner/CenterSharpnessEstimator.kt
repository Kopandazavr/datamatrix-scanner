package com.kopandazavr.datamatrixscanner.scanner

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal const val CENTER_SHARP_THRESHOLD = 12f
internal const val CENTER_BLUR_THRESHOLD = 8f
internal const val CENTER_CHANGE_THRESHOLD = 6f
internal const val MANUAL_FOCUS_SHARP_THRESHOLD = 14f
internal const val MANUAL_FOCUS_BEST_RATIO = .80f

/**
 * Focus-time sharpness signal for the object deliberately placed under the centre cross.
 * [core] is intentionally small; [context] only helps when it agrees with the core and is
 * capped so a crisp shelf/background around a blurry Data Matrix cannot win the focus search.
 */
data class TargetSharpnessProfile(
    val score: Float,
    val core: Float,
    val context: Float
)

internal fun manualFocusThreshold(bestSharpness: Float): Float =
    max(MANUAL_FOCUS_SHARP_THRESHOLD, bestSharpness * MANUAL_FOCUS_BEST_RATIO)

internal fun needsManualAf(
    currentSharpness: Float?,
    bestSharpness: Float,
    motionRefocusNeeded: Boolean,
    focusFailureRefocusNeeded: Boolean
): Boolean = currentSharpness == null ||
    currentSharpness < manualFocusThreshold(bestSharpness) ||
    motionRefocusNeeded || focusFailureRefocusNeeded

/**
 * Cheap focus signal for the small area covered by the centre aim.
 *
 * The Y plane is sampled directly, without allocating a Bitmap. Mean absolute
 * Laplacian reacts strongly to crisp Data Matrix module edges and falls when
 * those edges are blurred.
 */
internal fun estimateCenterSharpness(
    luma: ByteBuffer,
    imageWidth: Int,
    imageHeight: Int,
    rowStride: Int,
    pixelStride: Int,
    cropLeft: Int = 0,
    cropTop: Int = 0,
    cropRight: Int = imageWidth,
    cropBottom: Int = imageHeight,
    regionFraction: Float = .12f
): Float? {
    if (imageWidth < 5 || imageHeight < 5 || rowStride <= 0 || pixelStride <= 0) return null

    val safeLeft = cropLeft.coerceIn(0, imageWidth - 1)
    val safeTop = cropTop.coerceIn(0, imageHeight - 1)
    val safeRight = cropRight.coerceIn(safeLeft + 1, imageWidth)
    val safeBottom = cropBottom.coerceIn(safeTop + 1, imageHeight)
    val cropWidth = safeRight - safeLeft
    val cropHeight = safeBottom - safeTop
    val diameter = (min(cropWidth, cropHeight) * regionFraction)
        .toInt()
        .coerceAtLeast(12)
        .coerceAtMost(min(cropWidth, cropHeight) - 2)
    if (diameter < 3) return null

    val centerX = safeLeft + cropWidth / 2
    val centerY = safeTop + cropHeight / 2
    val half = diameter / 2
    val left = (centerX - half).coerceAtLeast(safeLeft + 1)
    val top = (centerY - half).coerceAtLeast(safeTop + 1)
    val right = (centerX + half).coerceAtMost(safeRight - 1)
    val bottom = (centerY + half).coerceAtMost(safeBottom - 1)
    if (right <= left || bottom <= top) return null

    fun yAt(x: Int, y: Int): Int {
        val index = y * rowStride + x * pixelStride
        if (index !in 0 until luma.limit()) throw IndexOutOfBoundsException()
        return luma.get(index).toInt() and 0xff
    }

    var weightedLaplacianSum = 0L
    var totalWeight = 0L
    return try {
        // Prefer the pixels directly under the cross. Clipping keeps one sharp shelf
        // or pack edge near the outside of the region from masking a blurry code.
        val innerRadius = (diameter / 6).coerceAtLeast(1)
        val middleRadius = (diameter / 3).coerceAtLeast(innerRadius + 1)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val center = yAt(x, y)
                val laplacian = 4 * center -
                    yAt(x - 1, y) - yAt(x + 1, y) -
                    yAt(x, y - 1) - yAt(x, y + 1)
                val distance = max(abs(x - centerX), abs(y - centerY))
                val weight = when {
                    distance <= innerRadius -> 4
                    distance <= middleRadius -> 2
                    else -> 1
                }
                weightedLaplacianSum += abs(laplacian).coerceAtMost(96) * weight
                totalWeight += weight
                x += 2
            }
            y += 2
        }
        if (totalWeight == 0L) null else weightedLaplacianSum.toFloat() / totalWeight
    } catch (_: IndexOutOfBoundsException) {
        null
    }
}

/**
 * Two-scale sharpness used by the manual focus sweep.
 *
 * The scanner UI asks the user to put the Data Matrix under the centre cross, therefore a small
 * central patch is a much stronger signal than detail farther away. The wider patch still helps
 * when the cross is not perfectly centred, but its contribution is capped relative to the core.
 */
internal fun estimateTargetSharpness(
    luma: ByteBuffer,
    imageWidth: Int,
    imageHeight: Int,
    rowStride: Int,
    pixelStride: Int,
    cropLeft: Int = 0,
    cropTop: Int = 0,
    cropRight: Int = imageWidth,
    cropBottom: Int = imageHeight,
    coreFraction: Float = .075f,
    contextFraction: Float = .16f
): TargetSharpnessProfile? {
    val core = estimateCenterSharpness(
        luma = luma,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        rowStride = rowStride,
        pixelStride = pixelStride,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropRight = cropRight,
        cropBottom = cropBottom,
        regionFraction = coreFraction
    ) ?: return null
    val context = estimateCenterSharpness(
        luma = luma,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        rowStride = rowStride,
        pixelStride = pixelStride,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropRight = cropRight,
        cropBottom = cropBottom,
        regionFraction = contextFraction
    ) ?: core

    // A very sharp background must not compensate for a blurry object directly under the cross.
    // The additive allowance keeps low-contrast but genuinely sharp codes usable.
    val cappedContext = min(context, core * 1.45f + 4f)
    val combined = core * .76f + cappedContext * .24f
    return TargetSharpnessProfile(combined, core, context)
}

internal fun updateCenterSharpState(wasSharp: Boolean, score: Float): Boolean =
    if (wasSharp) score >= CENTER_BLUR_THRESHOLD else score >= CENTER_SHARP_THRESHOLD

internal fun updateCenterSharpState(
    wasSharp: Boolean,
    score: Float,
    sharpThreshold: Float,
    blurThreshold: Float
): Boolean = if (wasSharp) score >= blurThreshold else score >= sharpThreshold

/**
 * Tiny exposure-independent centre snapshot used to notice that the object under
 * the cross moved even when a blurred high-contrast code fools the sharpness score.
 */
internal fun sampleCenterLuma(
    luma: ByteBuffer,
    imageWidth: Int,
    imageHeight: Int,
    rowStride: Int,
    pixelStride: Int,
    cropLeft: Int = 0,
    cropTop: Int = 0,
    cropRight: Int = imageWidth,
    cropBottom: Int = imageHeight,
    regionFraction: Float = .14f,
    gridSize: Int = 12
): ByteArray? {
    if (
        imageWidth < 2 || imageHeight < 2 || rowStride <= 0 || pixelStride <= 0 ||
        gridSize < 2
    ) return null

    val safeLeft = cropLeft.coerceIn(0, imageWidth - 1)
    val safeTop = cropTop.coerceIn(0, imageHeight - 1)
    val safeRight = cropRight.coerceIn(safeLeft + 1, imageWidth)
    val safeBottom = cropBottom.coerceIn(safeTop + 1, imageHeight)
    val cropWidth = safeRight - safeLeft
    val cropHeight = safeBottom - safeTop
    val diameter = (min(cropWidth, cropHeight) * regionFraction)
        .toInt()
        .coerceAtLeast(gridSize)
        .coerceAtMost(min(cropWidth, cropHeight))
    if (diameter < gridSize) return null

    val centerX = safeLeft + cropWidth / 2
    val centerY = safeTop + cropHeight / 2
    val left = (centerX - diameter / 2).coerceIn(safeLeft, safeRight - 1)
    val top = (centerY - diameter / 2).coerceIn(safeTop, safeBottom - 1)
    val values = ByteArray(gridSize * gridSize)

    return try {
        var index = 0
        for (gridY in 0 until gridSize) {
            val y = (top + (gridY + .5f) * diameter / gridSize)
                .toInt()
                .coerceAtMost(safeBottom - 1)
            for (gridX in 0 until gridSize) {
                val x = (left + (gridX + .5f) * diameter / gridSize)
                    .toInt()
                    .coerceAtMost(safeRight - 1)
                val bufferIndex = y * rowStride + x * pixelStride
                if (bufferIndex !in 0 until luma.limit()) throw IndexOutOfBoundsException()
                values[index++] = luma.get(bufferIndex)
            }
        }
        values
    } catch (_: IndexOutOfBoundsException) {
        null
    }
}

/** Mean absolute frame difference after removing a uniform exposure shift. */
internal fun estimateCenterChange(previous: ByteArray, current: ByteArray): Float? {
    if (previous.isEmpty() || previous.size != current.size) return null
    var deltaSum = 0L
    for (index in current.indices) {
        deltaSum += (current[index].toInt() and 0xff) - (previous[index].toInt() and 0xff)
    }
    val meanDelta = deltaSum.toFloat() / current.size
    var deviationSum = 0f
    for (index in current.indices) {
        val delta = (current[index].toInt() and 0xff) - (previous[index].toInt() and 0xff)
        deviationSum += abs(delta - meanDelta)
    }
    return deviationSum / current.size
}
