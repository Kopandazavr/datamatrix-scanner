package com.kopandazavr.datamatrixscanner.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryRegionTest {
    @Test
    fun paddedSquareStaysInsideImage() {
        val padded = RecoveryRegion(2f, 5f, 22f, 15f).paddedSquare(100, 80, .25f)

        assertEquals(padded.width, padded.height, .001f)
        assertTrue(padded.left >= 0f)
        assertTrue(padded.top >= 0f)
        assertTrue(padded.right <= 100f)
        assertTrue(padded.bottom <= 80f)
    }

    @Test
    fun candidateCropKeepsCentreAndAddsWideMargin() {
        val region = RecoveryRegion(30f, 30f, 70f, 50f)
        val padded = region.paddedSquare(100, 100, CANDIDATE_CROP_PADDING)

        assertEquals(region.centerX, padded.centerX, .001f)
        assertEquals(region.centerY, padded.centerY, .001f)
        assertEquals(72f, padded.width, .001f)
        assertEquals(72f, padded.height, .001f)
    }

    @Test
    fun mergeRemovesOnlyNearDuplicateRegions() {
        val regions = mergeRecoveryRegions(
            listOf(
                RecoveryRegion(10f, 10f, 50f, 50f),
                RecoveryRegion(11f, 11f, 51f, 51f),
                RecoveryRegion(46f, 10f, 86f, 50f)
            ),
            imageWidth = 100,
            imageHeight = 100
        )

        assertEquals(2, regions.size)
    }

    @Test
    fun tilePyramidCoversEveryGridCell() {
        val tiles = overlappingTiles(1200, 900)

        assertEquals(4 + 9 + 16, tiles.size)
        assertTrue(tiles.all { it.left >= 0 && it.top >= 0 && it.right <= 1200 && it.bottom <= 900 })
    }

    @Test
    fun potentialRegionBecomesNormalizedWhiteOverlayBox() {
        val box = RecoveryRegion(20f, 10f, 60f, 50f)
            .toPotentialDetectionBox(100, 80, "test")

        assertEquals(DetectionHighlight.POTENTIAL, box.highlight)
        assertEquals(.2f, box.points[0].x, .001f)
        assertEquals(.125f, box.points[0].y, .001f)
        assertEquals(.6f, box.points[2].x, .001f)
        assertEquals(.625f, box.points[2].y, .001f)
        assertEquals(1.25f, box.imageAspect, .001f)
    }
}
