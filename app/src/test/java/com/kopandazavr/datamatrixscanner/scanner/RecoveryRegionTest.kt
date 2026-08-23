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
}
