package com.kopandazavr.datamatrixscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceBenchmarkPolicyTest {
    private fun level(workers: Int, rate: Double, p95: Double = 20.0, correct: Int = 10) =
        WorkerLevelResult(workers, rate, p95, errors = 0, correct = correct, attempted = 10)

    @Test fun stopsAfterTwoConsecutiveNonImprovements() {
        assertFalse(shouldStopWorkerSweep(listOf(level(1, 10.0), level(2, 15.0))))
        assertTrue(shouldStopWorkerSweep(listOf(level(1, 10.0), level(2, 10.2), level(3, 10.25))))
    }

    @Test fun invalidFastResultCannotWin() {
        val selected = recommendedWorkerLevel(
            listOf(level(1, 10.0), level(2, 20.0, correct = 9), level(3, 14.0))
        )
        assertEquals(3, selected?.workers)
    }

    @Test fun allIncorrectSweepIsExplicitFallbackNotRecommendation() {
        val levels = listOf(level(1, 10.0, correct = 8), level(2, 14.0, correct = 8), level(3, 13.0, correct = 8))
        val selected = recommendedWorkerLevel(levels)
        assertEquals(null, selected)
        assertTrue(benchmarkFallbackReason(false, selected, level(1, 9.0, correct = 15).copy(attempted = 18))!!.contains("ни один"))
    }

    @Test fun invalidSustainedRejectsOtherwiseValidPeak() {
        val peak = level(2, 20.0)
        val sustained = WorkerLevelResult(2, 15.0, 25.0, errors = 0, correct = 15, attempted = 18)
        assertTrue(benchmarkFallbackReason(false, peak, sustained)!!.contains("15/18"))
        assertEquals(null, benchmarkFallbackReason(false, peak, level(2, 18.0)))
    }
}
