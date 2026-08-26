package com.kopandazavr.datamatrixscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFocusModeTest {
    @Test
    fun fastModeUsesShorterManualSweepAndPreciseAddsFinePass() {
        assertTrue(ManualFocusMode.PRECISE.coarseSegments > ManualFocusMode.FAST.coarseSegments)
        assertFalse(ManualFocusMode.FAST.finePass)
        assertTrue(ManualFocusMode.PRECISE.finePass)
        assertTrue(ManualFocusMode.PRECISE.nominalProgressMs > ManualFocusMode.FAST.nominalProgressMs)
    }

    @Test
    fun preferenceFallsBackToPreciseMode() {
        assertEquals(ManualFocusMode.PRECISE, ManualFocusMode.fromPreference(null))
        assertEquals(ManualFocusMode.PRECISE, ManualFocusMode.fromPreference("UNKNOWN"))
        assertEquals(ManualFocusMode.PRECISE, ManualFocusMode.fromPreference("PRECISE"))
    }
}
