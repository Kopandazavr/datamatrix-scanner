package com.kopandazavr.datamatrixscanner.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCandidateBindingTest {
    @Test fun originalAnalyzerIndexesSurviveAreaDifferences() {
        val small = RecoveryRegion(10f, 10f, 30f, 30f)
        val large = RecoveryRegion(100f, 100f, 180f, 180f)
        val bindings = listOf(
            CandidateEvidenceBinding(0, 11, 2, CandidateEvidenceState.ELIGIBLE, 2f, 2, 0, false, 0f, 0f, small),
            CandidateEvidenceBinding(1, 22, 5, CandidateEvidenceState.ELIGIBLE, 3f, 3, 0, false, 0f, 0f, large)
        )

        val result = bindLiveCandidates(listOf(small, large), bindings)

        assertEquals(listOf(0, 1), result.map { it.candidateIndex })
        assertEquals(listOf(11, 22), result.map { it.binding.trackId })
        assertEquals(listOf(small, large), result.map { it.region })
    }

    @Test fun missingBindingDoesNotReindexFollowingCandidate() {
        val regions = listOf(
            RecoveryRegion(0f, 0f, 20f, 20f),
            RecoveryRegion(40f, 40f, 80f, 80f),
            RecoveryRegion(100f, 100f, 160f, 160f)
        )
        val binding = CandidateEvidenceBinding(
            2, 99, 7, CandidateEvidenceState.ELIGIBLE, 2f, 2, 0, false, 0f, 0f, regions[2]
        )

        val result = bindLiveCandidates(regions, listOf(binding))

        assertEquals(1, result.size)
        assertEquals(2, result.single().candidateIndex)
        assertEquals(99, result.single().binding.trackId)
    }
}
