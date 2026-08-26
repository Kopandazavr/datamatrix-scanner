package com.kopandazavr.datamatrixscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedFocusPolicyTest {
    @Test fun requiresSixIndependentEpisodes() {
        val samples = (1..5).map { FocusEpisodeSample(8f + it * .01f, it.toLong()) }
        assertNull(recommendLearnedFocus(samples, 12f, 10L).anchor)
    }

    @Test fun rejectsMultimodalHistory() {
        val samples = listOf(2f, 2.1f, 2.2f, 8f, 8.1f, 8.2f)
            .mapIndexed { index, value -> FocusEpisodeSample(value, index.toLong()) }
        val result = recommendLearnedFocus(samples, 12f, 20L)
        assertNull(result.anchor)
        assertEquals("multimodal", result.reason)
    }

    @Test fun returnsRobustAnchorAndWorkingBand() {
        val samples = listOf(7.7f, 7.8f, 7.9f, 8f, 8.05f, 8.1f, 11f)
            .mapIndexed { index, value -> FocusEpisodeSample(value, index.toLong()) }
        val result = recommendLearnedFocus(samples, 12f, 20L)
        assertNotNull(result.anchor)
        assertTrue(result.anchor!! in 7.8f..8.05f)
        assertTrue(result.confidence >= .60f)
    }

    @Test fun afRegionUsesConfirmedP10OnlyAfterEnoughSamples() {
        val samples = (0 until 20).map {
            AfSizeSample(.10f + (it % 5) * .005f, (it / 4).toLong(), passiveHit = it % 2 == 0)
        }
        val result = recommendAfRegion(samples, currentSize = .20f)
        assertEquals("robust_p10", result.reason)
        assertTrue(result.normalizedSize in .08f..12f)
    }
}
