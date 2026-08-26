package com.kopandazavr.datamatrixscanner

import com.kopandazavr.datamatrixscanner.scanner.DetectionBox
import com.kopandazavr.datamatrixscanner.scanner.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerStatisticsMathTest {
    @Test
    fun histogramKeepsRobustPercentilesAndOutlierCounts() {
        val histogram = NumericHistogram(0f, 10f, 10)
        listOf(-2f, 0f, 2f, 4f, 6f, 8f, 10f, 20f).forEach(histogram::add)

        val snapshot = histogram.snapshot()

        assertEquals(8L, snapshot.count)
        assertEquals(1L, snapshot.underflow)
        assertEquals(1L, snapshot.overflow)
        assertTrue(requireNotNull(snapshot.p10) <= requireNotNull(snapshot.p50))
        assertTrue(requireNotNull(snapshot.p50) <= requireNotNull(snapshot.p90))
        assertTrue(requireNotNull(snapshot.robustMin) <= requireNotNull(snapshot.robustMax))
    }

    @Test
    fun geometryIsNormalizedAndSeparatesShapeFromPosition() {
        val box = DetectionBox(
            points = listOf(
                NormalizedPoint(.4f, .45f),
                NormalizedPoint(.6f, .45f),
                NormalizedPoint(.6f, .55f),
                NormalizedPoint(.4f, .55f)
            ),
            key = "candidate",
            imageAspect = 16f / 9f
        )

        val geometry = requireNotNull(detectionGeometry(box))

        assertEquals(.2f, geometry.width, .0001f)
        assertEquals(.1f, geometry.height, .0001f)
        assertEquals(.02f, geometry.area, .0001f)
        assertEquals(0f, geometry.centerDistance, .0001f)
        assertEquals(3.5556f, geometry.aspectRatio, .001f)
    }
}
