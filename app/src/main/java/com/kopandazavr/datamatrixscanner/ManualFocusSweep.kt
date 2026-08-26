package com.kopandazavr.datamatrixscanner

import com.kopandazavr.datamatrixscanner.scanner.FocusCandidateScore
import com.kopandazavr.datamatrixscanner.scanner.MANUAL_FOCUS_SHARP_THRESHOLD
import com.kopandazavr.datamatrixscanner.scanner.compareFocusCandidateScores
import kotlin.math.abs
import kotlin.math.max

internal data class FocusSweepSample(
    val distance: Float,
    val candidateScore: FocusCandidateScore,
    val targetSharpness: Float? = null,
    val targetCoreSharpness: Float? = null,
    val targetContextSharpness: Float? = null,
    val requestId: Long? = null,
    val requestedDistance: Float? = null,
    val actualDistance: Float? = null,
    val lensState: Int? = null,
    val analyzerFrameId: Long? = null,
    val cameraFrameNumber: Long? = null,
    val sensorTimestampNs: Long? = null,
    val commandAckMs: Long? = null,
    val settleMs: Long? = null,
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
    val metadataFallback: Boolean = false
)

internal fun coarseFocusDistances(minimumFocusDistance: Float, segments: Int = 7): List<Float> {
    if (!minimumFocusDistance.isFinite() || minimumFocusDistance <= 0f || segments < 1) return emptyList()
    return (segments downTo 0).map { index -> minimumFocusDistance * index / segments }
}

internal fun fineFocusDistances(
    minimumFocusDistance: Float,
    bestDistance: Float,
    coarseStep: Float
): List<Float> {
    if (!minimumFocusDistance.isFinite() || minimumFocusDistance <= 0f || !bestDistance.isFinite()) return emptyList()
    // A precise pass now checks the closest bracket around the coarse maximum first. The older
    // four-point fine walk added visible pauses but rarely changed the final lens position.
    val offsets = listOf(-.28f, .28f)
    return offsets
        .map { (bestDistance + coarseStep * it).coerceIn(0f, minimumFocusDistance) }
        .filter { abs(it - bestDistance) > .0001f }
        .distinctBy { (it * 10_000f).toInt() }
}

/**
 * Manual focus is intentionally target-biased. A real decode remains the strongest signal, and a
 * large difference in the number of Data Matrix candidates still wins. For otherwise comparable
 * positions, sharpness directly under the cross is allowed to override one noisy candidate so a
 * crisp background cannot drag the lens away from the object the user is aiming at.
 */
internal fun compareFocusSweepSamples(a: FocusSweepSample, b: FocusSweepSample): Int {
    val ac = a.candidateScore
    val bc = b.candidateScore

    ac.stableDecodedCount.compareTo(bc.stableDecodedCount).takeIf { it != 0 }?.let { return it }
    ac.decodedHits.compareTo(bc.decodedHits).takeIf { it != 0 }?.let { return it }

    val stableDifference = ac.stableCount - bc.stableCount
    if (abs(stableDifference) >= 2) return stableDifference.compareTo(0)

    compareTargetSharpness(a.targetSharpness, b.targetSharpness).takeIf { it != 0 }?.let { return it }
    return compareFocusCandidateScores(ac, bc)
}

internal fun bestFocusSweepSample(samples: List<FocusSweepSample>): FocusSweepSample? =
    samples.maxWithOrNull { a, b -> compareFocusSweepSamples(a, b) }

/**
 * A score is strong after an actual multi-frame confirmation of at least one candidate, after
 * any successful decode, or when a one-frame probe sees several independent candidate zones.
 */
internal fun hasStrongFocusEvidence(score: FocusCandidateScore): Boolean =
    score.stableDecodedCount > 0 ||
        score.decodedHits > 0 ||
        (score.frameCount >= 2 && score.hasStableCandidate) ||
        score.stableCount >= 2

/**
 * Before moving the lens, keep the current physical position only when both signals agree: there
 * is confirmed Data Matrix geometry and the small target area under the cross is clearly sharp.
 */
internal fun shouldKeepCurrentFocus(score: FocusCandidateScore, targetSharpness: Float?): Boolean =
    targetSharpness != null && targetSharpness >= MANUAL_FOCUS_SHARP_THRESHOLD &&
        score.frameCount >= 2 && (
            score.stableDecodedCount > 0 || score.decodedHits > 0 || score.stableCount >= 2
        )

internal fun nextFocusDegradationRun(
    previousRun: Int,
    comparisonToBest: Int,
    bestHasEvidence: Boolean
): Int = when {
    comparisonToBest > 0 -> 0
    comparisonToBest < 0 && bestHasEvidence -> previousRun + 1
    else -> 0
}

internal fun shouldReverseFocusDirection(degradationRun: Int, requiredWorseProbes: Int = 2): Boolean =
    degradationRun >= requiredWorseProbes

/** One clearly bad target-sharpness jump is enough to turn immediately instead of waiting twice. */
internal fun isClearlyWorseFocusSample(sample: FocusSweepSample, best: FocusSweepSample): Boolean {
    val current = sample.targetSharpness ?: return false
    val peak = best.targetSharpness ?: return false
    if (peak < 6f) return false
    val absoluteDrop = peak - current
    val relativeDrop = if (peak <= 0f) 0f else absoluteDrop / peak
    val candidateLost = sample.candidateScore.stableCount < best.candidateScore.stableCount ||
        (best.candidateScore.decodedHits > 0 && sample.candidateScore.decodedHits == 0)
    return absoluteDrop >= max(2.8f, peak * .18f) || (candidateLost && relativeDrop >= .10f)
}

private fun compareTargetSharpness(a: Float?, b: Float?): Int {
    if (a == null && b == null) return 0
    if (a == null) return -1
    if (b == null) return 1
    val difference = a - b
    val materialDifference = max(1.15f, max(a, b) * .08f)
    return if (abs(difference) >= materialDifference) difference.compareTo(0f) else 0
}
