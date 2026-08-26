package com.kopandazavr.datamatrixscanner

import com.kopandazavr.datamatrixscanner.scanner.FocusCandidateScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFocusSweepTest {
    private fun score(
        stable: Int,
        hits: Int = stable * 3,
        decodedHits: Int = 0,
        stableDecoded: Int = 0,
        frames: Int = 3
    ) = FocusCandidateScore(
        stableCount = stable,
        stableDecodedCount = stableDecoded,
        decodedHits = decodedHits,
        consecutiveStrength = hits,
        totalHits = hits,
        frameCount = frames,
        consistency = 1f
    )

    @Test
    fun coarseSweepCoversNearAndInfinity() {
        val distances = coarseFocusDistances(10f, segments = 5)
        assertEquals(6, distances.size)
        assertEquals(10f, distances.first(), 0.0001f)
        assertEquals(0f, distances.last(), 0.0001f)
    }

    @Test
    fun fineSweepStaysInsideDeviceRangeAndUsesShortBracket() {
        val distances = fineFocusDistances(8f, bestDistance = 7.8f, coarseStep = 2f)
        assertTrue(distances.isNotEmpty())
        assertTrue(distances.size <= 2)
        assertTrue(distances.all { it in 0f..8f })
        assertTrue(distances.distinct().size == distances.size)
    }

    @Test
    fun targetSharpnessBreaksCandidateTie() {
        val best = bestFocusSweepSample(
            listOf(
                FocusSweepSample(8f, score(stable = 1), targetSharpness = 7f),
                FocusSweepSample(4f, score(stable = 1), targetSharpness = 18f),
                FocusSweepSample(0f, score(stable = 1), targetSharpness = 10f)
            )
        )
        assertEquals(4f, best?.distance ?: -1f, 0.0001f)
    }

    @Test
    fun oneNoisyExtraCandidateDoesNotBeatMuchSharperTarget() {
        val blurryTwo = FocusSweepSample(8f, score(stable = 2), targetSharpness = 6f)
        val sharpOne = FocusSweepSample(4f, score(stable = 1), targetSharpness = 18f)
        assertTrue(compareFocusSweepSamples(sharpOne, blurryTwo) > 0)
    }

    @Test
    fun twoExtraCandidatesStillBeatSharpnessWhenNothingDecoded() {
        val many = FocusSweepSample(8f, score(stable = 3), targetSharpness = 7f)
        val aimed = FocusSweepSample(4f, score(stable = 1), targetSharpness = 20f)
        assertTrue(compareFocusSweepSamples(many, aimed) > 0)
    }

    @Test
    fun actualDecodeBeatsSharpness() {
        val decoded = FocusSweepSample(8f, score(stable = 1, decodedHits = 1), targetSharpness = 7f)
        val sharp = FocusSweepSample(4f, score(stable = 1), targetSharpness = 25f)
        assertTrue(compareFocusSweepSamples(decoded, sharp) > 0)
    }

    @Test
    fun currentFocusNeedsCandidatesAndSharpTargetToStayPut() {
        val confirmed = FocusCandidateScore(
            stableCount = 3, stableDecodedCount = 0, decodedHits = 0,
            consecutiveStrength = 6, totalHits = 6, frameCount = 2, consistency = 1f
        )
        assertTrue(!shouldKeepCurrentFocus(confirmed, targetSharpness = 7f))
        assertTrue(shouldKeepCurrentFocus(confirmed, targetSharpness = 18f))
        assertTrue(hasStrongFocusEvidence(confirmed))
    }

    @Test
    fun oneConfirmedUndecodedCandidateStillAllowsLocalSearch() {
        val confirmed = FocusCandidateScore(
            stableCount = 1, stableDecodedCount = 0, decodedHits = 0,
            consecutiveStrength = 2, totalHits = 2, frameCount = 2, consistency = 1f
        )
        assertTrue(!shouldKeepCurrentFocus(confirmed, targetSharpness = 25f))
        assertTrue(hasStrongFocusEvidence(confirmed))
    }

    @Test
    fun oneFrameSingleCandidateIsEvidenceButNotYetStrong() {
        val probe = FocusCandidateScore(
            stableCount = 1, stableDecodedCount = 0, decodedHits = 0,
            consecutiveStrength = 1, totalHits = 1, frameCount = 1, consistency = 1f
        )
        assertTrue(probe.hasEvidence)
        assertTrue(!hasStrongFocusEvidence(probe))
    }

    @Test
    fun twoWorseProbesTriggerEarlyDirectionChangeOnceBestHasEvidence() {
        var run = nextFocusDegradationRun(0, comparisonToBest = -1, bestHasEvidence = true)
        assertEquals(1, run)
        run = nextFocusDegradationRun(run, comparisonToBest = -1, bestHasEvidence = true)
        assertTrue(shouldReverseFocusDirection(run))
        assertEquals(0, nextFocusDegradationRun(run, comparisonToBest = 1, bestHasEvidence = true))
    }

    @Test
    fun largeSharpnessDropCanTurnAfterOneProbe() {
        val best = FocusSweepSample(4f, score(stable = 1), targetSharpness = 20f)
        val worse = FocusSweepSample(5f, score(stable = 1), targetSharpness = 12f)
        assertTrue(isClearlyWorseFocusSample(worse, best))
    }
}
