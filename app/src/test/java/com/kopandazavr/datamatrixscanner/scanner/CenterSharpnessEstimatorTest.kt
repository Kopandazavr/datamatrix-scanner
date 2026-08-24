package com.kopandazavr.datamatrixscanner.scanner

import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CenterSharpnessEstimatorTest {
    @Test
    fun crispCentralModulesAreSharperThanSmoothCentre() {
        val width = 80
        val height = 80
        val sharp = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if ((x / 4 + y / 4) % 2 == 0) 24.toByte() else 232.toByte()
        }
        val smooth = sharp.copyOf().also { pixels ->
            for (y in 30 until 50) {
                for (x in 30 until 50) pixels[y * width + x] = 128.toByte()
            }
        }

        val sharpScore = estimateCenterSharpness(ByteBuffer.wrap(sharp), width, height, width, 1)
        val smoothScore = estimateCenterSharpness(ByteBuffer.wrap(smooth), width, height, width, 1)

        assertNotNull(sharpScore)
        assertNotNull(smoothScore)
        assertTrue(requireNotNull(sharpScore) >= CENTER_SHARP_THRESHOLD)
        assertTrue(requireNotNull(smoothScore) < CENTER_BLUR_THRESHOLD)
    }

    @Test
    fun hysteresisDoesNotChatterNearOneThreshold() {
        assertTrue(updateCenterSharpState(wasSharp = false, score = CENTER_SHARP_THRESHOLD))
        assertTrue(updateCenterSharpState(wasSharp = true, score = CENTER_BLUR_THRESHOLD))
        assertFalse(updateCenterSharpState(wasSharp = true, score = CENTER_BLUR_THRESHOLD - .1f))
        assertFalse(updateCenterSharpState(wasSharp = false, score = CENTER_SHARP_THRESHOLD - .1f))
    }
}
