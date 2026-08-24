package com.kopandazavr.datamatrixscanner.scanner

import kotlin.math.max
import kotlin.math.min

internal const val CANDIDATE_CROP_PADDING = .40f

internal data class PixelPoint(val x: Float, val y: Float)

internal data class RecoveryRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val corners: List<PixelPoint> = emptyList()
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun paddedSquare(imageWidth: Int, imageHeight: Int, padding: Float): RecoveryRegion {
        val side = max(width, height) * (1f + padding * 2f)
        val half = side / 2f
        var outputLeft = centerX - half
        var outputTop = centerY - half
        var outputRight = centerX + half
        var outputBottom = centerY + half
        if (outputLeft < 0f) {
            outputRight -= outputLeft
            outputLeft = 0f
        }
        if (outputTop < 0f) {
            outputBottom -= outputTop
            outputTop = 0f
        }
        if (outputRight > imageWidth) {
            outputLeft -= outputRight - imageWidth
            outputRight = imageWidth.toFloat()
        }
        if (outputBottom > imageHeight) {
            outputTop -= outputBottom - imageHeight
            outputBottom = imageHeight.toFloat()
        }
        return RecoveryRegion(
            left = outputLeft.coerceAtLeast(0f),
            top = outputTop.coerceAtLeast(0f),
            right = outputRight.coerceAtMost(imageWidth.toFloat()),
            bottom = outputBottom.coerceAtMost(imageHeight.toFloat()),
            corners = corners
        )
    }
}

internal fun mergeRecoveryRegions(
    input: List<RecoveryRegion>,
    imageWidth: Int,
    imageHeight: Int,
    maxRegions: Int = 24
): List<RecoveryRegion> {
    val accepted = mutableListOf<RecoveryRegion>()
    input
        .filter { it.width >= 8f && it.height >= 8f && it.area < imageWidth * imageHeight * .96f }
        .sortedByDescending { it.area }
        .forEach { candidate ->
            if (accepted.size >= maxRegions) return@forEach
            if (accepted.none { intersectionOverUnion(it, candidate) >= .72f }) accepted += candidate
        }
    return accepted
}

internal fun overlappingTiles(
    imageWidth: Int,
    imageHeight: Int,
    grids: IntRange = 2..4,
    overlap: Float = .18f
): List<RecoveryRegion> = buildList {
    grids.forEach { grid ->
        val tileWidth = imageWidth.toFloat() / grid
        val tileHeight = imageHeight.toFloat() / grid
        val padX = tileWidth * overlap / 2f
        val padY = tileHeight * overlap / 2f
        for (row in 0 until grid) for (column in 0 until grid) {
            add(
                RecoveryRegion(
                    left = (column * tileWidth - padX).coerceAtLeast(0f),
                    top = (row * tileHeight - padY).coerceAtLeast(0f),
                    right = ((column + 1) * tileWidth + padX).coerceAtMost(imageWidth.toFloat()),
                    bottom = ((row + 1) * tileHeight + padY).coerceAtMost(imageHeight.toFloat())
                )
            )
        }
    }
}

internal fun RecoveryRegion.toPotentialDetectionBox(
    imageWidth: Int,
    imageHeight: Int,
    keyPrefix: String
): DetectionBox {
    val boxCorners = corners.takeIf { it.size >= 4 } ?: listOf(
        PixelPoint(left, top),
        PixelPoint(right, top),
        PixelPoint(right, bottom),
        PixelPoint(left, bottom)
    )
    return DetectionBox(
        points = boxCorners.take(4).map { point ->
            NormalizedPoint(
                x = (point.x / imageWidth.coerceAtLeast(1)).coerceIn(0f, 1f),
                y = (point.y / imageHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
            )
        },
        key = "$keyPrefix:${left.toInt()}:${top.toInt()}:${right.toInt()}:${bottom.toInt()}",
        imageAspect = imageWidth.toFloat() / imageHeight.coerceAtLeast(1),
        highlight = DetectionHighlight.POTENTIAL
    )
}

private fun intersectionOverUnion(first: RecoveryRegion, second: RecoveryRegion): Float {
    val intersectionWidth = (min(first.right, second.right) - max(first.left, second.left)).coerceAtLeast(0f)
    val intersectionHeight = (min(first.bottom, second.bottom) - max(first.top, second.top)).coerceAtLeast(0f)
    val intersection = intersectionWidth * intersectionHeight
    val union = first.area + second.area - intersection
    return if (union <= 0f) 0f else intersection / union
}
