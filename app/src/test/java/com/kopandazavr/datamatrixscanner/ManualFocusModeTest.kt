package com.kopandazavr.datamatrixscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFocusModeTest {
    @Test
    fun fastModeIsSinglePassAndPreciseModeKeepsQualityPass() {
        assertEquals(1, ManualFocusMode.FAST.maxAttempts)
        assertEquals(0L, ManualFocusMode.FAST.settleDelayMs)
        assertTrue(ManualFocusMode.PRECISE.maxAttempts > ManualFocusMode.FAST.maxAttempts)
        assertTrue(ManualFocusMode.PRECISE.nominalProgressMs > ManualFocusMode.FAST.nominalProgressMs)
    }

    @Test
    fun preferenceFallsBackToFastMode() {
        assertEquals(ManualFocusMode.FAST, ManualFocusMode.fromPreference(null))
        assertEquals(ManualFocusMode.FAST, ManualFocusMode.fromPreference("UNKNOWN"))
        assertEquals(ManualFocusMode.PRECISE, ManualFocusMode.fromPreference("PRECISE"))
    }
}
