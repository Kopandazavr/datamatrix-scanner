package com.kopandazavr.datamatrixscanner

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusRecoveryPolicyTest {
    @Test fun failedNativeAfPrefersUsefulPreAfActual() {
        val result = selectNativeAfRecovery(
            preAfActual = 10f, preAfSharpness = 8f, preAfCandidateEvidence = true,
            homeDistance = 7f, savedSuccessfulDistance = 8f, learnedAnchorDistance = 9f,
            lastRequestedDistance = 6f, minimumFocusDistance = 12f
        )
        assertEquals("pre_af_actual", result.source)
        assertEquals(10f, result.distance ?: -1f, .001f)
    }

    @Test fun failedNativeAfUsesKnownGoodWhenPreAfWasNotValidated() {
        val result = selectNativeAfRecovery(
            preAfActual = 0f, preAfSharpness = 1f, preAfCandidateEvidence = false,
            homeDistance = null, savedSuccessfulDistance = 10f, learnedAnchorDistance = 8f,
            lastRequestedDistance = 1f, minimumFocusDistance = 12f
        )
        assertEquals("last_success", result.source)
        assertEquals(10f, result.distance ?: -1f, .001f)
    }

    @Test fun manualSearchUsesTrustedAnchorBeforeBadCurrentActual() {
        assertEquals(
            10f,
            selectManualSearchAnchor(null, 10f, 8f, null, 1.4f, 12f),
            .001f
        )
    }

    @Test fun manualSearchStillAllowsCloseUpWhenItIsOnlyAvailableAnchor() {
        assertEquals(
            1.2f,
            selectManualSearchAnchor(null, null, null, null, 1.2f, 12f),
            .001f
        )
    }
}
