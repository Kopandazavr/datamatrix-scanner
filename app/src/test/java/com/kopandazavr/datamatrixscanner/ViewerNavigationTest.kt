package com.kopandazavr.datamatrixscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerNavigationTest {
    @Test
    fun removingFirstKeepsFirstPosition() {
        assertEquals(0, nextViewerIndexAfterRemoval(currentIndex = 0, remainingCount = 5))
    }

    @Test
    fun removingMiddleKeepsLogicalPosition() {
        assertEquals(1, nextViewerIndexAfterRemoval(currentIndex = 1, remainingCount = 5))
    }

    @Test
    fun removingLastMovesToPreviousPosition() {
        assertEquals(4, nextViewerIndexAfterRemoval(currentIndex = 5, remainingCount = 5))
    }

    @Test
    fun removingOnlyItemClosesViewer() {
        assertNull(nextViewerIndexAfterRemoval(currentIndex = 0, remainingCount = 0))
    }
}
