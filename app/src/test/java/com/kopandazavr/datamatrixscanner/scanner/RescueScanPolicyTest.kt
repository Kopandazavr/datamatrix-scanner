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
    fun `heavy rescue never auto starts in live scanning`() {
        assertFalse(RescueScanPolicy.shouldStart(false, ScanEnhancementMode.OFF))
        assertFalse(RescueScanPolicy.shouldStart(false, ScanEnhancementMode.BALANCED))
        assertFalse(RescueScanPolicy.shouldStart(false, ScanEnhancementMode.AGGRESSIVE))
        assertFalse(RescueScanPolicy.shouldStart(true, ScanEnhancementMode.AGGRESSIVE))
    }

    @Test
    fun `recognizes typical gs1 gtin and serial prefix`() {
        assertTrue(looksLikeGs1("010460165303951321ABC".toByteArray()))
        assertTrue(looksLikeGs1(byteArrayOf(29) + "010460165303951321ABC".toByteArray()))
        assertFalse(looksLikeGs1("010460165303951399ABC".toByteArray()))
        assertFalse(looksLikeGs1("plain text".toByteArray()))
    }
}
