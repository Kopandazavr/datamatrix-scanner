package com.kopandazavr.datamatrixscanner.scanner

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

internal const val CENTER_SHARP_THRESHOLD = 10f
internal const val CENTER_BLUR_THRESHOLD = 7f

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
    regionFraction: Float = .18f
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

    var laplacianSum = 0L
    var samples = 0
    return try {
        // Every second pixel is enough for the decision while keeping the work tiny.
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val center = yAt(x, y)
                val laplacian = 4 * center -
                    yAt(x - 1, y) - yAt(x + 1, y) -
                    yAt(x, y - 1) - yAt(x, y + 1)
                laplacianSum += abs(laplacian)
                samples += 1
                x += 2
            }
            y += 2
        }
        if (samples == 0) null else laplacianSum.toFloat() / samples
    } catch (_: IndexOutOfBoundsException) {
        null
    }
}

internal fun updateCenterSharpState(wasSharp: Boolean, score: Float): Boolean =
    if (wasSharp) score >= CENTER_BLUR_THRESHOLD else score >= CENTER_SHARP_THRESHOLD
