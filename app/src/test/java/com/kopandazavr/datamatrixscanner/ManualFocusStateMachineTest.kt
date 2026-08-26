package com.kopandazavr.datamatrixscanner

import com.kopandazavr.datamatrixscanner.scanner.FocusCandidateScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFocusStateMachineTest {
    private fun score(stable: Int = 0, decoded: Int = 0, frames: Int = 3) = FocusCandidateScore(
        stableCount = stable,
        stableDecodedCount = if (decoded > 0) 1 else 0,
        decodedHits = decoded,
        consecutiveStrength = stable * frames,
        totalHits = stable * frames,
        frameCount = frames,
        consistency = if (stable > 0) 1f else 0f
    )

    private fun sample(
        distance: Float,
        sharpness: Float? = null,
        stable: Int = 0,
        decoded: Int = 0
    ) = FocusSweepSample(
        distance = distance,
        candidateScore = score(stable, decoded),
        targetSharpness = sharpness,
        requestedDistance = distance,
        actualDistance = distance
    )

    @Test
    fun deadlineRetainsUsefulBestInsteadOfRestoringStaleDistance() {
        val start = sample(2f, sharpness = 4f)
        val state = ManualFocusStateMachine(2f, 2f, 8f, start)
        state.observe(sample(10f, sharpness = 36f, stable = 1, decoded = 1))

        val decision = state.finalizeDecision(FocusFinalizeReason.SEARCH_DEADLINE)

        assertEquals(10f, decision.distance ?: -1f, .0001f)
        assertTrue(decision.retainedBestProbe)
        assertFalse(decision.usedStartActual)
        assertFalse(decision.usedSavedFallback)
    }

    @Test
    fun deadlineWithoutUsefulProbeRestoresPhysicalStartBeforeSavedFallback() {
        val start = sample(3f, sharpness = 4f)
        val state = ManualFocusStateMachine(3f, 7f, 9f, start)
        state.observe(sample(5f, sharpness = 4.5f))

        val decision = state.finalizeDecision(FocusFinalizeReason.SEARCH_DEADLINE)

        assertEquals(3f, decision.distance ?: -1f, .0001f)
        assertEquals(FocusFinalizeReason.NO_USEFUL_PROBE, decision.reason)
        assertTrue(decision.usedStartActual)
    }

    @Test
    fun unknownActualKeepsRequestedSeparateFromSavedFallback() {
        val state = ManualFocusStateMachine(null, 4f, 8f, null)
        val decision = state.finalizeDecision(FocusFinalizeReason.NO_USEFUL_PROBE)

        assertEquals(4f, decision.distance ?: -1f, .0001f)
        assertFalse(decision.usedSavedFallback)
    }

    @Test
    fun savedDistanceIsOnlyLastFallbackWhenActualAndRequestedAreUnknown() {
        val state = ManualFocusStateMachine(null, null, 8f, null)
        val decision = state.finalizeDecision(FocusFinalizeReason.NO_USEFUL_PROBE)

        assertEquals(8f, decision.distance ?: -1f, .0001f)
        assertTrue(decision.usedSavedFallback)
    }

    @Test
    fun boundaryPeakAtInfinityIsRetained() {
        val state = ManualFocusStateMachine(5f, 5f, 7f, sample(5f, sharpness = 4f))
        state.observe(sample(0f, sharpness = 19f, stable = 1))

        assertEquals(0f, state.finalizeDecision(FocusFinalizeReason.SEARCH_COMPLETE).distance ?: -1f, .0001f)
    }

    @Test
    fun decodedProbeAllowsImmediateConfirm() {
        assertTrue(shouldConfirmFocusEarly(sample(6f, sharpness = 8f, decoded = 1), sample(5f, sharpness = 7f)))
    }

    @Test
    fun finePassNeedsUsefulAnchorAndEnoughBudget() {
        val useful = sample(6f, sharpness = 12f, stable = 1)
        assertTrue(shouldRunFineFocusPass(ManualFocusMode.PRECISE, useful, false, 800L))
        assertFalse(shouldRunFineFocusPass(ManualFocusMode.PRECISE, useful, true, 800L))
        assertFalse(shouldRunFineFocusPass(ManualFocusMode.PRECISE, useful, false, 200L))
        assertFalse(shouldRunFineFocusPass(ManualFocusMode.FAST, useful, false, 800L))
    }

    @Test
    fun commandAccountingAndRequestGatePreventConcurrentFocus() {
        val state = ManualFocusStateMachine(2f, 2f, 2f, sample(2f, sharpness = 4f))
        state.commandSent()
        state.commandFailed()
        assertEquals(1, state.lensCommands)
        assertEquals(1, state.commandFailures)

        val gate = FocusRequestGate()
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun autofocusLossTriggerRequiresThreeFramesAndRearmsAfterEvidence() {
        assertFalse(shouldTriggerAutoFocusForCandidateLoss(1, false, false))
        assertFalse(shouldTriggerAutoFocusForCandidateLoss(2, false, false))
        assertTrue(shouldTriggerAutoFocusForCandidateLoss(3, false, false))
        assertFalse(shouldTriggerAutoFocusForCandidateLoss(3, true, false))
        assertFalse(shouldTriggerAutoFocusForCandidateLoss(3, false, true))
        assertFalse(shouldTriggerAutoFocusForCandidateLoss(0, false, false))
        assertFalse(shouldRearmAutoFocusAfterCandidateEvidence(1, requiredEvidenceFrames = 3))
        assertFalse(shouldRearmAutoFocusAfterCandidateEvidence(2, requiredEvidenceFrames = 3))
        assertTrue(shouldRearmAutoFocusAfterCandidateEvidence(3, requiredEvidenceFrames = 3))
    }

    @Test
    fun autofocusLossTriggerRespectsCooldown() {
        assertFalse(
            shouldTriggerAutoFocusForCandidateLoss(
                missingFrames = 3,
                alreadyTriggeredForCurrentLoss = false,
                focusBusy = false,
                nowElapsedMs = 5_000L,
                lastTriggeredElapsedMs = 2_000L,
                cooldownMs = 4_000L
            )
        )
        assertTrue(
            shouldTriggerAutoFocusForCandidateLoss(
                missingFrames = 3,
                alreadyTriggeredForCurrentLoss = false,
                focusBusy = false,
                nowElapsedMs = 6_000L,
                lastTriggeredElapsedMs = 2_000L,
                cooldownMs = 4_000L
            )
        )
    }
}
