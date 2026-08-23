package com.kopandazavr.datamatrixscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RangeSelectionStateTest {
    private val rows = (1L..10L).toList()

    @Test
    fun longPressAddsRangesAndTapMovesAnchorWithoutClearingEarlierGroups() {
        var state = RangeSelectionState().selectRange(1, rows)
        state = state.selectRange(3, rows)
        state = state.toggle(5, rows)
        state = state.selectRange(7, rows)

        assertEquals(setOf(1L, 2L, 3L, 5L, 6L, 7L), state.selected)
        assertEquals(7L, state.anchorId)
    }

    @Test
    fun deselectingInsideRangeKeepsLastBoundaryAsAnchor() {
        var state = RangeSelectionState().selectRange(1, rows).selectRange(3, rows)
        state = state.toggle(2, rows)
        state = state.selectRange(5, rows)

        assertEquals(setOf(1L, 3L, 4L, 5L), state.selected)
        assertEquals(5L, state.anchorId)
    }

    @Test
    fun holesDoNotMoveLowerBoundary() {
        var state = RangeSelectionState().selectRange(1, rows).selectRange(5, rows)
        state = state.toggle(4, rows).toggle(3, rows).toggle(2, rows)
        state = state.selectRange(7, rows)

        assertEquals(setOf(1L, 5L, 6L, 7L), state.selected)
    }

    @Test
    fun removingAnchorUsesNextSelectedThenPreviousAndLastTapExitsMode() {
        var state = RangeSelectionState(setOf(1, 2, 3, 5), anchorId = 3)
        state = state.toggle(3, rows)
        assertEquals(5L, state.anchorId)

        state = RangeSelectionState(setOf(1, 2, 3), anchorId = 3).toggle(3, rows)
        assertEquals(2L, state.anchorId)

        state = RangeSelectionState(setOf(8), anchorId = 8).toggle(8, rows)
        assertFalse(state.isActive)
        assertEquals(null, state.anchorId)
    }

    @Test
    fun rangeWorksInEitherVisualDirection() {
        val state = RangeSelectionState().selectRange(7, rows).selectRange(4, rows)
        assertEquals(setOf(4L, 5L, 6L, 7L), state.selected)
        assertEquals(4L, state.anchorId)
    }

    @Test
    fun selectAllCompletesPartialSelectionAndSecondTapClearsIt() {
        val partial = RangeSelectionState(setOf(2L, 4L), anchorId = 4L)
        val all = partial.toggleAll(rows)

        assertEquals(rows.toSet(), all.selected)
        assertEquals(10L, all.anchorId)

        val cleared = all.toggleAll(rows)
        assertFalse(cleared.isActive)
        assertEquals(null, cleared.anchorId)
    }
}
