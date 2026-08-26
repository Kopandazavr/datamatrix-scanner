package com.kopandazavr.datamatrixscanner

import kotlin.math.abs
import kotlin.math.ceil

internal data class FocusEpisodeSample(
    val distance: Float,
    val observedAtMs: Long
)

internal data class LearnedFocusRecommendation(
    val anchor: Float?,
    val workingLow: Float?,
    val workingHigh: Float?,
    val confidence: Float,
    val validEpisodes: Int,
    val dominantEpisodes: Int,
    val reason: String
)

internal data class AfSizeSample(
    val normalizedShortSide: Float,
    val episodeId: Long,
    val passiveHit: Boolean
)

internal data class AfRegionRecommendation(
    val normalizedSize: Float,
    val sampleCount: Int,
    val supportingEpisodes: Int,
    val reason: String
)

internal fun recommendLearnedFocus(
    rawSamples: List<FocusEpisodeSample>,
    minimumFocusDistance: Float?,
    nowMs: Long,
    minimumEpisodes: Int = 6,
    windowSize: Int = 24
): LearnedFocusRecommendation {
    val samples = rawSamples.asSequence()
        .filter { it.distance.isFinite() && it.distance >= 0f }
        .sortedBy { it.observedAtMs }
        .toList()
        .takeLast(windowSize)
    if (samples.size < minimumEpisodes) {
        return LearnedFocusRecommendation(null, null, null, 0f, samples.size, 0, "insufficient_episodes")
    }
    val tolerance = maxOf(.08f, (minimumFocusDistance ?: 8f) * .035f)
    val sorted = samples.sortedBy { it.distance }
    var bestStart = 0
    var bestEnd = 0
    var right = 0
    for (left in sorted.indices) {
        if (right < left) right = left
        while (right + 1 < sorted.size && sorted[right + 1].distance - sorted[left].distance <= tolerance * 2f) right++
        if (right - left > bestEnd - bestStart) {
            bestStart = left
            bestEnd = right
        }
    }
    val dominant = sorted.subList(bestStart, bestEnd + 1)
    val confidence = dominant.size.toFloat() / samples.size
    if (confidence < .60f) {
        return LearnedFocusRecommendation(null, null, null, confidence, samples.size, dominant.size, "multimodal")
    }
    val values = dominant.map { it.distance }.sorted()
    val agePenalty = samples.maxOfOrNull { nowMs - it.observedAtMs }
        ?.let { if (it > 30L * 24L * 60L * 60L * 1_000L) .10f else 0f } ?: 0f
    return LearnedFocusRecommendation(
        anchor = percentile(values, .50f),
        workingLow = percentile(values, .10f),
        workingHigh = percentile(values, .90f),
        confidence = (confidence - agePenalty).coerceIn(0f, 1f),
        validEpisodes = samples.size,
        dominantEpisodes = dominant.size,
        reason = "ready"
    )
}

internal fun recommendAfRegion(
    rawSamples: List<AfSizeSample>,
    currentSize: Float = .20f,
    minimumSamples: Int = 20,
    minimumSupportingEpisodes: Int = 2,
    hardwareFloor: Float = .08f
): AfRegionRecommendation {
    val samples = rawSamples.filter { it.normalizedShortSide.isFinite() && it.normalizedShortSide in .02f..1f }
        .takeLast(100)
    if (samples.size < minimumSamples) {
        return AfRegionRecommendation(currentSize.coerceIn(hardwareFloor, .45f), samples.size, 0, "fixed_until_calibrated")
    }
    val values = samples.map { it.normalizedShortSide }.sorted()
    val proposed = percentile(values, .10f)?.coerceIn(hardwareFloor, .45f) ?: currentSize
    val lowerSupport = samples.filter { it.normalizedShortSide <= proposed * 1.12f }
    val episodes = lowerSupport.map { it.episodeId }.distinct().size
    if (lowerSupport.size < 3 || episodes < minimumSupportingEpisodes) {
        return AfRegionRecommendation(currentSize.coerceIn(hardwareFloor, .45f), samples.size, episodes, "lower_bound_unconfirmed")
    }
    // Hysteresis: a small noisy change does not resize the native AF region.
    val selected = if (abs(proposed - currentSize) < .018f) currentSize else proposed
    return AfRegionRecommendation(selected.coerceIn(hardwareFloor, .45f), samples.size, episodes, "robust_p10")
}

internal fun robustEpisodeDistance(values: List<Float>): Float? = percentile(
    values.filter { it.isFinite() && it >= 0f }.sorted(),
    .50f
)

private fun percentile(values: List<Float>, fraction: Float): Float? {
    if (values.isEmpty()) return null
    val index = (ceil(values.size * fraction.coerceIn(0f, 1f)).toInt() - 1).coerceIn(0, values.lastIndex)
    return values[index]
}
