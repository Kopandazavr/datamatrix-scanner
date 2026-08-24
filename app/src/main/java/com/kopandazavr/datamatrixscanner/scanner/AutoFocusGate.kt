package com.kopandazavr.datamatrixscanner.scanner

/**
 * Debounces automatic refocus and, crucially, prevents a persistent blur/noise
 * signal from causing endless focus cycles. After a focus session finishes the
 * gate only rearms after the centre has been stably healthy again.
 */
internal class AutoFocusGate(
    private val triggerHoldMs: Long = 700L,
    private val rearmStableMs: Long = 1_000L,
    private val cooldownMs: Long = 2_500L
) {
    private var armed = true
    private var blurSince: Long? = null
    private var sharpSince: Long? = null
    private var cooldownUntil = 0L

    fun reset() {
        armed = true
        blurSince = null
        sharpSince = null
        cooldownUntil = 0L
    }

    fun markSessionStarted(nowMs: Long) {
        armed = false
        blurSince = null
        sharpSince = null
        cooldownUntil = maxOf(cooldownUntil, nowMs)
    }

    fun markSessionFinished(nowMs: Long) {
        armed = false
        blurSince = null
        sharpSince = null
        cooldownUntil = nowMs + cooldownMs
    }

    fun shouldRequest(needsRefocus: Boolean, nowMs: Long): Boolean {
        if (!needsRefocus) {
            blurSince = null
            if (armed) return false
            if (nowMs < cooldownUntil) {
                sharpSince = null
                return false
            }
            val stableFrom = sharpSince ?: nowMs.also { sharpSince = it }
            if (nowMs - stableFrom >= rearmStableMs) {
                armed = true
                sharpSince = null
            }
            return false
        }

        sharpSince = null
        if (!armed || nowMs < cooldownUntil) return false
        val blurredFrom = blurSince ?: nowMs.also { blurSince = it }
        if (nowMs - blurredFrom < triggerHoldMs) return false

        markSessionStarted(nowMs)
        return true
    }
}
