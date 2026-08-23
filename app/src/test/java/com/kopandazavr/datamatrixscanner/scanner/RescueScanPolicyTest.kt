package com.kopandazavr.datamatrixscanner.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueScanPolicyTest {
    @Test
    fun `balanced profile has six variants and twelve decoder attempts`() {
        assertEquals(6, ScanEnhancementMode.BALANCED.variantCount)
        assertEquals(12, ScanEnhancementMode.BALANCED.decoderAttemptCount)
    }

    @Test
    fun `rescue waits for stagnation and never overlaps`() {
        val now = 10_000L
        assertFalse(RescueScanPolicy.shouldStart(now, 9_700L, 0L, false, ScanEnhancementMode.BALANCED))
        assertFalse(RescueScanPolicy.shouldStart(now, 9_000L, 9_700L, false, ScanEnhancementMode.BALANCED))
        assertFalse(RescueScanPolicy.shouldStart(now, 9_000L, 0L, true, ScanEnhancementMode.BALANCED))
        assertTrue(RescueScanPolicy.shouldStart(now, 9_000L, 0L, false, ScanEnhancementMode.BALANCED))
    }

    @Test
    fun `recognizes typical gs1 gtin and serial prefix`() {
        assertTrue(looksLikeGs1("010460165303951321ABC".toByteArray()))
        assertTrue(looksLikeGs1(byteArrayOf(29) + "010460165303951321ABC".toByteArray()))
        assertFalse(looksLikeGs1("010460165303951399ABC".toByteArray()))
        assertFalse(looksLikeGs1("plain text".toByteArray()))
    }
}
