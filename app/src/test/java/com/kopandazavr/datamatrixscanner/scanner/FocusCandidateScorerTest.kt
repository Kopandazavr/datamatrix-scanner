package com.kopandazavr.datamatrixscanner.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusCandidateScorerTest {
    private fun box(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        decoded: Boolean = false
    ) = FocusCandidateObservation(l, t, r, b, decoded)

    @Test
    fun needsThreeConsecutiveFramesAtStableCoordinates() {
        val tracker = FocusCandidateWindowTracker(requiredConsecutiveFrames = 3)
        tracker.addFrame(listOf(box(.20f, .20f, .40f, .40f)))
        tracker.addFrame(listOf(box(.205f, .20f, .405f, .40f)))
        assertFalse(tracker.score().hasStableCandidate)
        tracker.addFrame(listOf(box(.21f, .20f, .41f, .40f)))
        assertEquals(1, tracker.score().stableCount)
    }

    @Test
    fun aMissBreaksConsecutiveConfirmation() {
        val tracker = FocusCandidateWindowTracker(requiredConsecutiveFrames = 3)
        tracker.addFrame(listOf(box(.20f, .20f, .40f, .40f)))
        tracker.addFrame(emptyList())
        tracker.addFrame(listOf(box(.20f, .20f, .40f, .40f)))
        tracker.addFrame(listOf(box(.20f, .20f, .40f, .40f)))
        assertEquals(0, tracker.score().stableCount)
    }

    @Test
    fun decodedAndPotentialVersionsOfSameZoneMerge() {
        val merged = mergeFocusCandidateObservations(
            listOf(
                box(.20f, .20f, .40f, .40f, decoded = false),
                box(.205f, .205f, .405f, .405f, decoded = true)
            )
        )
        assertEquals(1, merged.size)
        assertTrue(merged.single().decoded)
    }

    @Test
    fun moreStableCoordinatesBeatMoreOneFrameNoise() {
        val stable = FocusCandidateScore(2, 0, 0, 6, 6, 3, .9f)
        val noisy = FocusCandidateScore(0, 0, 0, 3, 20, 3, 1f)
        assertTrue(compareFocusCandidateScores(stable, noisy) > 0)
    }
}
