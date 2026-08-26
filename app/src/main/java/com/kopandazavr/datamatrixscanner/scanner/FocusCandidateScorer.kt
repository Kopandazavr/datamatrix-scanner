package com.kopandazavr.datamatrixscanner.scanner

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class FocusCandidateObservation(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val decoded: Boolean = false
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

data class FocusCandidateFrameSnapshot(
    val frameId: Long,
    val capturedElapsedMs: Long,
    val sensorTimestampNs: Long = 0L,
    val observations: List<FocusCandidateObservation>
)

data class FocusCandidateScore(
    val stableCount: Int,
    val stableDecodedCount: Int,
    val decodedHits: Int,
    val consecutiveStrength: Int,
    val totalHits: Int,
    val frameCount: Int,
    val consistency: Float,
    val apparentArea: Float? = null,
    val centerDistance: Float? = null
) {
    val hasEvidence: Boolean get() = totalHits > 0
    val hasStableCandidate: Boolean get() = stableCount > 0

    /** Human-readable scalar for logs only. Selection itself uses compareFocusCandidateScores. */
    val points: Int
        get() = stableCount * 100_000 +
            stableDecodedCount * 10_000 +
            decodedHits * 1_000 +
            consecutiveStrength * 100 +
            totalHits * 10 +
            (consistency.coerceIn(0f, 1f) * 9f).toInt()
}

/**
 * Focus-time tracker. A Data Matrix-like zone counts as stable only after it stays at roughly
 * the same coordinates for [requiredConsecutiveFrames] consecutive analyzed frames.
 *
 * This is intentionally independent from pixel sharpness. It follows what the user actually
 * wants from focus: stable Data Matrix geometry under the camera, not a globally sharp scene.
 */
class FocusCandidateWindowTracker(
    private val requiredConsecutiveFrames: Int = 3,
    private val maxCenterDistance: Float = .12f
) {
    private data class Track(
        var rect: FocusCandidateObservation,
        var consecutive: Int,
        var maxConsecutive: Int,
        var totalHits: Int,
        var decodedHits: Int,
        var currentDecodedConsecutive: Int,
        var maxDecodedConsecutive: Int,
        var centerTravel: Float,
        var centerMoves: Int,
        var lastSeenFrame: Int
    )

    private val tracks = mutableListOf<Track>()
    private var frameIndex = 0

    val framesSeen: Int get() = frameIndex

    fun addFrame(observations: List<FocusCandidateObservation>) {
        frameIndex++
        val valid = mergeFocusCandidateObservations(observations)
        val matchedTrackIndexes = mutableSetOf<Int>()
        val matchedObservationIndexes = mutableSetOf<Int>()

        valid.forEachIndexed { observationIndex, observation ->
            val best = tracks.indices
                .asSequence()
                .filterNot { it in matchedTrackIndexes }
                .map { it to matchScore(tracks[it].rect, observation) }
                .filter { it.second > 0f }
                .maxByOrNull { it.second }
                ?.first

            if (best != null) {
                val track = tracks[best]
                matchedTrackIndexes += best
                matchedObservationIndexes += observationIndex
                val wasConsecutive = track.lastSeenFrame == frameIndex - 1
                val movement = centerDistance(track.rect, observation)
                if (wasConsecutive) {
                    track.consecutive += 1
                    track.centerTravel += movement
                    track.centerMoves += 1
                } else {
                    track.consecutive = 1
                    track.currentDecodedConsecutive = 0
                }
                track.maxConsecutive = max(track.maxConsecutive, track.consecutive)
                track.totalHits += 1
                if (observation.decoded) {
                    track.decodedHits += 1
                    track.currentDecodedConsecutive = if (wasConsecutive) track.currentDecodedConsecutive + 1 else 1
                    track.maxDecodedConsecutive = max(track.maxDecodedConsecutive, track.currentDecodedConsecutive)
                } else {
                    track.currentDecodedConsecutive = 0
                }
                track.rect = blend(track.rect, observation, .42f)
                track.lastSeenFrame = frameIndex
            }
        }

        valid.forEachIndexed { observationIndex, observation ->
            if (observationIndex in matchedObservationIndexes) return@forEachIndexed
            tracks += Track(
                rect = observation,
                consecutive = 1,
                maxConsecutive = 1,
                totalHits = 1,
                decodedHits = if (observation.decoded) 1 else 0,
                currentDecodedConsecutive = if (observation.decoded) 1 else 0,
                maxDecodedConsecutive = if (observation.decoded) 1 else 0,
                centerTravel = 0f,
                centerMoves = 0,
                lastSeenFrame = frameIndex
            )
        }

        tracks.forEachIndexed { index, track ->
            if (index !in matchedTrackIndexes && track.lastSeenFrame != frameIndex) {
                track.consecutive = 0
                track.currentDecodedConsecutive = 0
            }
        }
    }

    fun score(): FocusCandidateScore {
        val stableTracks = tracks.filter { it.maxConsecutive >= requiredConsecutiveFrames }
        val stableDecoded = stableTracks.count { it.maxDecodedConsecutive >= requiredConsecutiveFrames || it.decodedHits >= requiredConsecutiveFrames }
        val decodedHits = tracks.sumOf { it.decodedHits }
        val consecutiveStrength = tracks.sumOf { min(it.maxConsecutive, requiredConsecutiveFrames) }
        val totalHits = tracks.sumOf { it.totalHits }
        val travel = tracks.sumOf { it.centerTravel.toDouble() }.toFloat()
        val moves = tracks.sumOf { it.centerMoves }
        val averageTravel = if (moves == 0) 0f else travel / moves
        val consistency = (1f - averageTravel / maxCenterDistance).coerceIn(0f, 1f)
        val representative = stableTracks.maxByOrNull { it.maxConsecutive }?.rect
            ?: tracks.maxByOrNull { it.totalHits }?.rect
        return FocusCandidateScore(
            stableCount = stableTracks.size,
            stableDecodedCount = stableDecoded,
            decodedHits = decodedHits,
            consecutiveStrength = consecutiveStrength,
            totalHits = totalHits,
            frameCount = frameIndex,
            consistency = consistency,
            apparentArea = representative?.area,
            centerDistance = representative?.let {
                val dx = it.centerX - .5f
                val dy = it.centerY - .5f
                sqrt(dx * dx + dy * dy)
            }
        )
    }

    private fun matchScore(a: FocusCandidateObservation, b: FocusCandidateObservation): Float {
        val overlap = iou(a, b)
        val nested = containsMostly(a, b) || containsMostly(b, a)
        val distance = centerDistance(a, b)
        return when {
            overlap >= .12f -> 3f + overlap
            nested && distance <= maxCenterDistance * 1.25f -> 2.5f - distance
            distance <= maxCenterDistance -> 1.5f - distance
            else -> 0f
        }
    }
}

fun compareFocusCandidateScores(a: FocusCandidateScore, b: FocusCandidateScore): Int {
    fun compareInt(x: Int, y: Int): Int = x.compareTo(y)
    return compareInt(a.stableCount, b.stableCount).takeIf { it != 0 }
        ?: compareInt(a.stableDecodedCount, b.stableDecodedCount).takeIf { it != 0 }
        ?: compareInt(a.decodedHits, b.decodedHits).takeIf { it != 0 }
        ?: compareInt(a.consecutiveStrength, b.consecutiveStrength).takeIf { it != 0 }
        ?: compareInt(a.totalHits, b.totalHits).takeIf { it != 0 }
        ?: a.consistency.compareTo(b.consistency)
}

fun mergeFocusCandidateObservations(
    observations: List<FocusCandidateObservation>
): List<FocusCandidateObservation> {
    val valid = observations
        .mapNotNull { item ->
            val left = min(item.left, item.right).coerceIn(0f, 1f)
            val right = max(item.left, item.right).coerceIn(0f, 1f)
            val top = min(item.top, item.bottom).coerceIn(0f, 1f)
            val bottom = max(item.top, item.bottom).coerceIn(0f, 1f)
            FocusCandidateObservation(left, top, right, bottom, item.decoded)
                .takeIf { it.width >= .004f && it.height >= .004f }
        }
        .sortedByDescending { if (it.decoded) 1 else 0 }

    val merged = mutableListOf<FocusCandidateObservation>()
    valid.forEach { candidate ->
        val duplicateIndex = merged.indexOfFirst { existing ->
            iou(existing, candidate) >= .35f ||
                (centerDistance(existing, candidate) <= .035f && sizeRatio(existing, candidate) >= .35f)
        }
        if (duplicateIndex < 0) {
            merged += candidate
        } else if (candidate.decoded && !merged[duplicateIndex].decoded) {
            merged[duplicateIndex] = candidate
        }
    }
    return merged
}

private fun blend(
    old: FocusCandidateObservation,
    fresh: FocusCandidateObservation,
    alpha: Float
) = FocusCandidateObservation(
    left = old.left * (1f - alpha) + fresh.left * alpha,
    top = old.top * (1f - alpha) + fresh.top * alpha,
    right = old.right * (1f - alpha) + fresh.right * alpha,
    bottom = old.bottom * (1f - alpha) + fresh.bottom * alpha,
    decoded = old.decoded || fresh.decoded
)

private fun sizeRatio(a: FocusCandidateObservation, b: FocusCandidateObservation): Float {
    val larger = max(a.area, b.area)
    return if (larger <= 0f) 0f else min(a.area, b.area) / larger
}

private fun centerDistance(a: FocusCandidateObservation, b: FocusCandidateObservation): Float {
    val dx = a.centerX - b.centerX
    val dy = a.centerY - b.centerY
    return sqrt(dx * dx + dy * dy)
}

private fun containsMostly(outer: FocusCandidateObservation, inner: FocusCandidateObservation): Boolean {
    if (inner.area <= 0f) return false
    return intersection(outer, inner) / inner.area >= .72f && inner.area <= outer.area * 1.45f
}

private fun iou(a: FocusCandidateObservation, b: FocusCandidateObservation): Float {
    val inter = intersection(a, b)
    val union = a.area + b.area - inter
    return if (union <= 0f) 0f else inter / union
}

private fun intersection(a: FocusCandidateObservation, b: FocusCandidateObservation): Float =
    max(0f, min(a.right, b.right) - max(a.left, b.left)) *
        max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
