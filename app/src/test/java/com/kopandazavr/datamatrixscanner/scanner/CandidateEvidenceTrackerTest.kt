package com.kopandazavr.datamatrixscanner.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateEvidenceTrackerTest {
    private fun box(x: Float = 20f, y: Float = 20f) = RecoveryRegion(x, y, x + 20f, y + 20f)

    @Test fun twoIndependentHitsMayHaveAMissBetweenThem() {
        val tracker = CandidateEvidenceTracker()
        val first = tracker.update(listOf(box()), 100, 100, 1, 100)
        assertEquals(CandidateEvidenceState.PROVISIONAL, first.bindings.single().state)

        val miss = tracker.update(emptyList(), 100, 100, 2, 200)
        assertTrue(miss.events.any { it.type == "EVIDENCE_DECAY" })

        val second = tracker.update(listOf(box(21f, 20f)), 100, 100, 3, 300)
        assertEquals(CandidateEvidenceState.ELIGIBLE, second.bindings.single().state)
        assertEquals(first.bindings.single().trackId, second.bindings.single().trackId)
    }

    @Test fun submissionIsExactlyOnceAcrossFlickerAndReassociation() {
        val tracker = CandidateEvidenceTracker()
        tracker.update(listOf(box()), 100, 100, 1, 100)
        val eligible = tracker.update(listOf(box(21f, 20f)), 100, 100, 2, 200).bindings.single()

        assertEquals("SUBMITTED", tracker.markSubmitted(eligible.identity)?.type)
        assertEquals("ALREADY_SUBMITTED", tracker.markSubmitted(eligible.identity)?.type)
        tracker.update(emptyList(), 100, 100, 3, 300)
        val returnHit = tracker.update(listOf(box(22f, 20f)), 100, 100, 4, 400).bindings.single()
        assertEquals(eligible.identity, returnHit.identity)
        assertEquals(CandidateEvidenceState.SUBMITTED, returnHit.state)
        assertEquals("ALREADY_SUBMITTED", tracker.markSubmitted(returnHit.identity)?.type)
    }

    @Test fun eligibleTrackEntersLostGraceBeforeRemoval() {
        val tracker = CandidateEvidenceTracker(graceOpportunities = 2, graceMs = 2_000)
        tracker.update(listOf(box()), 100, 100, 1, 100)
        tracker.update(listOf(box()), 100, 100, 2, 200)
        val miss = tracker.update(emptyList(), 100, 100, 3, 300)
        assertTrue(miss.events.any { it.type == "LOST_GRACE" })
        assertEquals(1, miss.stableBoxes.size)
    }

    @Test fun distinctCandidatesKeepDistinctIdentities() {
        val tracker = CandidateEvidenceTracker()
        val first = tracker.update(listOf(box(5f, 5f), box(70f, 70f)), 100, 100, 1, 100)
        val second = tracker.update(listOf(box(71f, 70f), box(6f, 5f)), 100, 100, 2, 200)
        assertEquals(first.bindings.map { it.trackId }.toSet(), second.bindings.map { it.trackId }.toSet())
    }
}
