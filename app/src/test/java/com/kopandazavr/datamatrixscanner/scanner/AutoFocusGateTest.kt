package com.kopandazavr.datamatrixscanner.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFocusGateTest {
    @Test
    fun sustainedBlurTriggersOnlyOnceUntilHealthyStateRearmsGate() {
        val gate = AutoFocusGate(triggerHoldMs = 700, rearmStableMs = 1_000, cooldownMs = 2_500)

        assertFalse(gate.shouldRequest(true, 0))
        assertFalse(gate.shouldRequest(true, 699))
        assertTrue(gate.shouldRequest(true, 700))
        gate.markSessionFinished(900)

        // A pessimistic/stale blur signal must not create endless focus cycles.
        assertFalse(gate.shouldRequest(true, 5_000))

        // Rearm only after the centre has been continuously healthy again.
        assertFalse(gate.shouldRequest(false, 5_100))
        assertFalse(gate.shouldRequest(false, 6_099))
        assertFalse(gate.shouldRequest(false, 6_100))
        assertFalse(gate.shouldRequest(true, 6_200))
        assertTrue(gate.shouldRequest(true, 6_900))
    }

    @Test
    fun shortNoisePulseDoesNotTriggerAutofocus() {
        val gate = AutoFocusGate(triggerHoldMs = 700, rearmStableMs = 1_000, cooldownMs = 2_500)

        assertFalse(gate.shouldRequest(true, 0))
        assertFalse(gate.shouldRequest(false, 300))
        assertFalse(gate.shouldRequest(true, 500))
        assertFalse(gate.shouldRequest(true, 1_100))
        assertTrue(gate.shouldRequest(true, 1_200))
    }

    @Test
    fun startingSessionImmediatelyDisarmsGate() {
        val gate = AutoFocusGate(triggerHoldMs = 700, rearmStableMs = 1_000, cooldownMs = 2_500)
        gate.markSessionStarted(0)

        assertFalse(gate.shouldRequest(true, 10_000))
    }
}
