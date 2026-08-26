package com.kopandazavr.datamatrixscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusOwnershipTest {
    @Test fun sliderTakeoverInvalidatesManualGenerationAndHoldsLens() {
        val state = FocusOwnershipState()
        state.setCapabilities(10f)
        state.metadata(4f, stale = false)
        val manual = state.beginManual()
        val (slider, target) = state.userTarget(7f)

        assertTrue(slider > manual)
        assertEquals(7f, target)
        assertFalse(state.completeManual(manual, 8f, success = true))
        assertEquals(FocusOwner.USER_HOLD, state.snapshot().owner)
        assertEquals(.7f, state.snapshot().normalizedRequested)
    }

    @Test fun persistentNativeAfStaysBlueUntilExplicitTakeover() {
        val state = FocusOwnershipState()
        state.setCapabilities(12f)
        val native = state.beginNativeAf()
        assertTrue(state.completeNativeAf(native))
        assertEquals(FocusOwner.NATIVE_AF, state.snapshot().owner)
        assertTrue(state.snapshot().nativeAfActive)

        state.metadata(5f, stale = false)
        val held = state.holdActual()
        assertTrue(held.first > native)
        assertEquals(FocusOwner.USER_HOLD, state.snapshot().owner)
        assertFalse(state.snapshot().nativeAfActive)
    }

    @Test fun homeSetAndClearAlwaysLeaveDeterministicHold() {
        val state = FocusOwnershipState()
        state.setCapabilities(10f)
        state.metadata(4f, stale = false)

        val saved = state.toggleHome()
        assertTrue(saved.accepted)
        assertEquals(4f, saved.homeDistance)
        assertEquals(FocusOwner.USER_HOLD, state.snapshot().owner)
        assertEquals(.4f, state.snapshot().normalizedHome)

        val cleared = state.toggleHome()
        assertTrue(cleared.cleared)
        assertNull(cleared.homeDistance)
        assertEquals(4f, cleared.heldDistance)
        assertEquals(FocusOwner.USER_HOLD, state.snapshot().owner)
    }

    @Test fun staleActualCannotMutateHome() {
        val state = FocusOwnershipState()
        state.setCapabilities(10f)
        state.restoreHome(6f)
        state.metadata(null, stale = true)
        assertFalse(state.toggleHome().accepted)
        assertEquals(6f, state.snapshot().homeDistance)
    }

    @Test fun sliderMapsInfinityAtTopAndNearAtBottom() {
        assertEquals(0f, lensDistanceFromSlider(0f, 10f))
        assertEquals(10f, lensDistanceFromSlider(1f, 10f))
        assertEquals(.25f, normalizeLensDistance(2.5f, 10f))
    }
}
