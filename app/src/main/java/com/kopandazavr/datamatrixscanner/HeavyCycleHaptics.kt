package com.kopandazavr.datamatrixscanner

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.delay

internal class HeavyCycleHaptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Strong opening pulse -> short quiet gap -> fast repeating medium pulses.
     * The repeating portion deliberately starts only after the opening pulse/gap.
     */
    fun start() {
        val target = vibrator ?: return
        if (!target.hasVibrator()) return
        val timings = longArrayOf(0L, 62L, 130L, 30L, 46L)
        val amplitudes = intArrayOf(0, 255, 0, 150, 0)
        target.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 3))
    }

    /**
     * Stop the medium pulse train, leave a small quiet gap, then emit one strong
     * closing pulse so completion is unmistakable.
     */
    suspend fun finish() {
        val target = vibrator ?: return
        target.cancel()
        if (!target.hasVibrator()) return
        delay(125L)
        target.vibrate(VibrationEffect.createOneShot(62L, 255))
    }

    fun cancel() {
        vibrator?.cancel()
    }
}
