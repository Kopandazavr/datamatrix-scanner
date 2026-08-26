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

    @Test
    fun detailUnderCrossOutweighsDetailNearRegionEdge() {
        val width = 120
        val height = 120
        val centreDetail = ByteArray(width * height) { 128.toByte() }
        val edgeDetail = centreDetail.copyOf()

        for (y in 52 until 68) {
            for (x in 52 until 68) {
                centreDetail[y * width + x] = if ((x / 2 + y / 2) % 2 == 0) 32.toByte() else 224.toByte()
            }
        }
        for (y in 52 until 68) {
            for (x in 38 until 54) {
                edgeDetail[y * width + x] = if ((x / 2 + y / 2) % 2 == 0) 32.toByte() else 224.toByte()
            }
        }

        val centreScore = estimateCenterSharpness(
            ByteBuffer.wrap(centreDetail), width, height, width, 1, regionFraction = .40f
        )
        val edgeScore = estimateCenterSharpness(
            ByteBuffer.wrap(edgeDetail), width, height, width, 1, regionFraction = .40f
        )

        assertNotNull(centreScore)
        assertNotNull(edgeScore)
        assertTrue(requireNotNull(centreScore) > requireNotNull(edgeScore))
    }

    @Test
    fun targetProfileDoesNotLetSharpBackgroundHideBlurryCross() {
        val width = 160
        val height = 160
        val blurryTargetSharpBackground = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if ((x / 2 + y / 2) % 2 == 0) 28.toByte() else 228.toByte()
        }
        // Deliberately blur the small object under the cross while leaving its surroundings crisp.
        for (y in 72 until 88) {
            for (x in 72 until 88) blurryTargetSharpBackground[y * width + x] = 128.toByte()
        }

        val sharpTargetSoftBackground = ByteArray(width * height) { 128.toByte() }
        for (y in 70 until 90) {
            for (x in 70 until 90) {
                sharpTargetSoftBackground[y * width + x] =
                    if ((x / 2 + y / 2) % 2 == 0) 28.toByte() else 228.toByte()
            }
        }

        val blurry = estimateTargetSharpness(
            ByteBuffer.wrap(blurryTargetSharpBackground), width, height, width, 1
        )
        val sharp = estimateTargetSharpness(
            ByteBuffer.wrap(sharpTargetSoftBackground), width, height, width, 1
        )

        assertNotNull(blurry)
        assertNotNull(sharp)
        assertTrue(requireNotNull(sharp).score > requireNotNull(blurry).score)
        assertTrue(sharp.core > blurry.core)
    }

    @Test
    fun centreChangeIgnoresUniformExposureShift() {
        val previous = ByteArray(144) { index -> (40 + index % 20).toByte() }
        val current = ByteArray(144) { index -> (60 + index % 20).toByte() }

        val change = estimateCenterChange(previous, current)

        assertNotNull(change)
        assertTrue(requireNotNull(change) < CENTER_CHANGE_THRESHOLD)
    }

    @Test
    fun centreChangeDetectsObjectMovement() {
        val previous = ByteArray(144) { index ->
            if ((index / 12) % 2 == 0) 30.toByte() else 220.toByte()
        }
        val current = ByteArray(144) { index ->
            if ((index / 12 + index % 12) % 2 == 0) 30.toByte() else 220.toByte()
        }

        val change = estimateCenterChange(previous, current)

        assertNotNull(change)
        assertTrue(requireNotNull(change) >= CENTER_CHANGE_THRESHOLD)
    }

    @Test
    fun manualAfSkipsOnlyClearlySharpCentre() {
        assertTrue(needsManualAf(13.9f, 14f, motionRefocusNeeded = false, focusFailureRefocusNeeded = false))
        assertFalse(needsManualAf(14f, 14f, motionRefocusNeeded = false, focusFailureRefocusNeeded = false))
        // A camera that has previously achieved a much sharper centre gets an adaptive bar.
        assertTrue(needsManualAf(19.9f, 25f, motionRefocusNeeded = false, focusFailureRefocusNeeded = false))
        assertFalse(needsManualAf(20f, 25f, motionRefocusNeeded = false, focusFailureRefocusNeeded = false))
    }

    @Test
    fun motionOrPreviousAfFailureForcesAnotherAfEvenWhenSharp() {
        assertTrue(needsManualAf(30f, 25f, motionRefocusNeeded = true, focusFailureRefocusNeeded = false))
        assertTrue(needsManualAf(30f, 25f, motionRefocusNeeded = false, focusFailureRefocusNeeded = true))
    }
}
