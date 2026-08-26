package com.kopandazavr.datamatrixscanner

import com.kopandazavr.datamatrixscanner.scanner.MANUAL_FOCUS_SHARP_THRESHOLD
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal class FocusRequestGate {
    private val held = AtomicBoolean(false)

    fun tryAcquire(): Boolean = held.compareAndSet(false, true)
    fun release() { held.set(false) }
}

internal fun shouldTriggerAutoFocusForCandidateLoss(
    missingFrames: Int,
    alreadyTriggeredForCurrentLoss: Boolean,
    focusBusy: Boolean,
    thresholdFrames: Int = 3,
    nowElapsedMs: Long = Long.MAX_VALUE,
    lastTriggeredElapsedMs: Long = Long.MIN_VALUE,
    cooldownMs: Long = 0L
): Boolean {
    val cooldownElapsed = lastTriggeredElapsedMs == Long.MIN_VALUE ||
        nowElapsedMs - lastTriggeredElapsedMs >= cooldownMs
    return missingFrames >= thresholdFrames && !alreadyTriggeredForCurrentLoss && !focusBusy && cooldownElapsed
}

internal fun shouldRearmAutoFocusAfterCandidateEvidence(
    consecutiveEvidenceFrames: Int,
    requiredEvidenceFrames: Int = 3
): Boolean = consecutiveEvidenceFrames >= requiredEvidenceFrames

internal enum class FocusFinalizeReason {
    EARLY_CONFIRMED,
    SEARCH_COMPLETE,
    SEARCH_DEADLINE,
    COMMAND_FAILURE,
    NO_USEFUL_PROBE
}

internal data class FocusFinalizeDecision(
    val distance: Float?,
    val reason: FocusFinalizeReason,
    val retainedBestProbe: Boolean,
    val usedStartActual: Boolean,
    val usedSavedFallback: Boolean
)

/**
 * Pure session policy: it owns best-probe retention and final-distance selection. Camera commands
 * and frame waits live in CameraFocusController, but cancellation/timeout cannot bypass this state.
 */
internal class ManualFocusStateMachine(
    val startActualDistance: Float?,
    val startRequestedDistance: Float?,
    val savedFallbackDistance: Float?,
    private val startSample: FocusSweepSample?
) {
    private val observed = mutableListOf<FocusSweepSample>()
    var bestProbe: FocusSweepSample? = startSample?.takeIf { it.distance.isFinite() }
        private set
    var commandFailures: Int = 0
        private set
    var lensCommands: Int = 0
        private set

    fun commandSent() { lensCommands++ }
    fun commandFailed() { commandFailures++ }

    fun observe(sample: FocusSweepSample): Int {
        observed += sample
        val previous = bestProbe
        val comparison = if (previous == null) 1 else compareFocusSweepSamples(sample, previous)
        if (previous == null || comparison > 0) bestProbe = sample
        return comparison
    }

    fun samples(): List<FocusSweepSample> = observed.toList()

    fun hasUsefulBestProbe(): Boolean {
        val best = bestProbe ?: return false
        if (best.candidateScore.decodedHits > 0 || best.candidateScore.stableDecodedCount > 0) return true
        if (best.candidateScore.hasStableCandidate) return true
        val sharp = best.targetSharpness ?: return false
        if (sharp >= MANUAL_FOCUS_SHARP_THRESHOLD) return true
        val startSharp = startSample?.targetSharpness ?: return sharp >= MIN_USEFUL_TARGET_SHARPNESS
        return sharp >= max(MIN_USEFUL_TARGET_SHARPNESS, startSharp * 1.22f) && sharp - startSharp >= 1.8f
    }

    fun finalizeDecision(reason: FocusFinalizeReason): FocusFinalizeDecision {
        val usefulBest = hasUsefulBestProbe()
        val bestDistance = bestProbe?.distance?.takeIf { it.isFinite() }
        val selected = when {
            usefulBest && bestDistance != null -> bestDistance
            startActualDistance != null -> startActualDistance
            startRequestedDistance != null -> startRequestedDistance
            else -> savedFallbackDistance
        }
        return FocusFinalizeDecision(
            distance = selected,
            reason = if (!usefulBest && reason == FocusFinalizeReason.SEARCH_DEADLINE) FocusFinalizeReason.NO_USEFUL_PROBE else reason,
            retainedBestProbe = usefulBest && selected == bestDistance,
            usedStartActual = !usefulBest && selected != null && selected == startActualDistance,
            usedSavedFallback = !usefulBest && startActualDistance == null && startRequestedDistance == null && selected == savedFallbackDistance
        )
    }

    companion object {
        private const val MIN_USEFUL_TARGET_SHARPNESS = 7f
    }
}

internal fun shouldConfirmFocusEarly(
    sample: FocusSweepSample,
    anchor: FocusSweepSample?
): Boolean {
    val score = sample.candidateScore
    if (score.decodedHits > 0 || score.stableDecodedCount > 0) return true
    val sharp = sample.targetSharpness ?: return false
    if (score.hasStableCandidate && sharp >= MANUAL_FOCUS_SHARP_THRESHOLD) return true
    val anchorSharp = anchor?.targetSharpness ?: return false
    val core = sample.targetCoreSharpness ?: sharp
    val anchorCore = anchor.targetCoreSharpness ?: anchorSharp
    return sharp >= MANUAL_FOCUS_SHARP_THRESHOLD &&
        sharp >= anchorSharp * 1.38f &&
        core >= max(anchorCore * 1.28f, anchorCore + 2f)
}

internal fun shouldRunFineFocusPass(
    mode: ManualFocusMode,
    best: FocusSweepSample?,
    earlyConfirmed: Boolean,
    remainingSearchMs: Long
): Boolean = mode.finePass && !earlyConfirmed && remainingSearchMs >= 420L && best != null && (
    best.candidateScore.hasEvidence || (best.targetSharpness ?: 0f) >= 6f
)
