package com.kopandazavr.datamatrixscanner.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPresentationTest {
    private fun box(
        left: Float, top: Float, right: Float, bottom: Float,
        highlight: DetectionHighlight = DetectionHighlight.POTENTIAL,
        alpha: Float = 1f
    ) = DetectionBox(
        points = listOf(
            NormalizedPoint(left, top), NormalizedPoint(right, top),
            NormalizedPoint(right, bottom), NormalizedPoint(left, bottom)
        ),
        key = "$left:$top", imageAspect = 1f, highlight = highlight,
        stableCandidate = true, overlayAlpha = alpha
    )

    @Test fun presentationRectIsAxisAlignedAndTenPercentLarger() {
        val tilted = DetectionBox(
            points = listOf(
                NormalizedPoint(.20f,.25f), NormalizedPoint(.40f,.20f),
                NormalizedPoint(.45f,.40f), NormalizedPoint(.25f,.45f)
            ), key = "tilted", imageAspect = 1f
        )
        val rect = tilted.axisAlignedPresentationRect()!!
        assertEquals(.275f, rect.width, .0001f)
        assertEquals(.275f, rect.height, .0001f)
        assertEquals(.1875f, rect.left, .0001f)
        assertEquals(.4625f, rect.right, .0001f)
    }

    @Test fun overlappingPotentialBoxesArePresentationSuppressed() {
        val kept = suppressOverlappingPresentationBoxes(listOf(
            box(.20f,.20f,.40f,.40f, alpha = 1f),
            box(.22f,.22f,.42f,.42f, alpha = .7f)
        ))
        assertEquals(1, kept.size)
        assertEquals(1f, kept.single().overlayAlpha, .0001f)
    }

    @Test fun decodedStatusWinsWhenItOverlapsPotentialTrack() {
        val potential = box(.20f,.20f,.40f,.40f, DetectionHighlight.POTENTIAL)
        val decoded = box(.21f,.21f,.41f,.41f, DetectionHighlight.ACTIVE)
        val kept = suppressOverlappingPresentationBoxes(listOf(potential, decoded))
        assertEquals(1, kept.size)
        assertEquals(DetectionHighlight.ACTIVE, kept.single().highlight)
    }

    @Test fun separatedBoxesBothRemainVisible() {
        val kept = suppressOverlappingPresentationBoxes(listOf(
            box(.10f,.10f,.20f,.20f), box(.70f,.70f,.80f,.80f)
        ))
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.stableCandidate })
    }
}
