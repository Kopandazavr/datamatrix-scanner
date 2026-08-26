package com.kopandazavr.datamatrixscanner.scanner

import kotlin.math.max
import kotlin.math.min

internal data class OverlayRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
}

internal fun DetectionBox.axisAlignedPresentationRect(expandFraction: Float = .10f): OverlayRect? {
    if (points.isEmpty()) return null
    val rawLeft = points.minOf { it.x }.coerceIn(0f, 1f)
    val rawTop = points.minOf { it.y }.coerceIn(0f, 1f)
    val rawRight = points.maxOf { it.x }.coerceIn(0f, 1f)
    val rawBottom = points.maxOf { it.y }.coerceIn(0f, 1f)
    val width = rawRight - rawLeft
    val height = rawBottom - rawTop
    if (width < .004f || height < .004f) return null
    val padX = width * expandFraction / 2f
    val padY = height * expandFraction / 2f
    return OverlayRect(
        left = (rawLeft - padX).coerceIn(0f, 1f),
        top = (rawTop - padY).coerceIn(0f, 1f),
        right = (rawRight + padX).coerceIn(0f, 1f),
        bottom = (rawBottom + padY).coerceIn(0f, 1f)
    )
}

/**
 * Presentation-only suppression. Decoder geometry is never changed. Status/decoded boxes win over
 * potential boxes; otherwise the freshest/most opaque candidate wins. Suppressed identities stay
 * alive in the shared evidence tracker's lost grace and reappear as soon as boxes separate.
 */
internal fun suppressOverlappingPresentationBoxes(
    boxes: List<DetectionBox>,
    noticeableOverlapFraction: Float = .06f
): List<DetectionBox> {
    if (boxes.size < 2) return boxes
    data class Ranked(val index: Int, val box: DetectionBox, val rect: OverlayRect)
    val ranked = boxes.mapIndexedNotNull { index, box ->
        box.axisAlignedPresentationRect()?.let { Ranked(index, box, it) }
    }.sortedWith(
        compareByDescending<Ranked> { if (it.box.highlight == DetectionHighlight.POTENTIAL) 0 else 1 }
            .thenByDescending { if (it.box.stableCandidate) 1 else 0 }
            .thenByDescending { it.box.overlayAlpha }
            .thenBy { it.index }
    )

    val accepted = mutableListOf<Ranked>()
    ranked.forEach { candidate ->
        val overlaps = accepted.any { kept ->
            noticeableOverlap(candidate.rect, kept.rect, noticeableOverlapFraction)
        }
        if (!overlaps) accepted += candidate
    }
    val acceptedIndexes = accepted.mapTo(hashSetOf()) { it.index }
    return boxes.filterIndexed { index, box ->
        box.axisAlignedPresentationRect() == null || index in acceptedIndexes
    }
}

private fun noticeableOverlap(a: OverlayRect, b: OverlayRect, threshold: Float): Boolean {
    val intersection = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
        max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
    val smaller = min(a.area, b.area)
    return smaller > 0f && intersection / smaller >= threshold
}
