package com.kopandazavr.datamatrixscanner.scanner

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Shared temporal identity used by presentation, live ROI collection and Boost. */
internal enum class CandidateEvidenceState { PROVISIONAL, ELIGIBLE, SUBMITTED, LOST_GRACE }

internal data class CandidateEvidenceEvent(
    val type: String,
    val trackId: Int,
    val generation: Int,
    val detail: String = ""
)

internal data class CandidateEvidenceBinding(
    val candidateIndex: Int,
    val trackId: Int,
    val generation: Int,
    val state: CandidateEvidenceState,
    val evidence: Float,
    val observations: Int,
    val misses: Int,
    val alreadySubmitted: Boolean,
    val centerJitter: Float,
    val scaleDrift: Float,
    val region: RecoveryRegion
) {
    val identity: CandidateIdentity get() = CandidateIdentity(trackId, generation)
    val eligibleForBoost: Boolean
        get() = state == CandidateEvidenceState.ELIGIBLE ||
            state == CandidateEvidenceState.SUBMITTED || state == CandidateEvidenceState.LOST_GRACE
}

internal data class CandidateIdentity(val trackId: Int, val generation: Int)

internal data class CandidateEvidenceUpdate(
    val bindings: List<CandidateEvidenceBinding>,
    val stableBoxes: List<DetectionBox>,
    val events: List<CandidateEvidenceEvent>
)

/**
 * Evidence is accumulated on independent hard-pass opportunities, not consecutive camera frames.
 * Misses decay confidence and enter a bounded grace state instead of destroying identity.
 */
internal class CandidateEvidenceTracker(
    private val eligibleEvidence: Float = 1.8f,
    private val retainEvidence: Float = .85f,
    private val hitEvidence: Float = 1f,
    private val missDecay: Float = .18f,
    private val graceOpportunities: Int = 3,
    private val graceMs: Long = 1_300L,
    private val maxCenterDistance: Float = .17f
) {
    private data class Rect(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w = (r - l).coerceAtLeast(0f)
        val h = (b - t).coerceAtLeast(0f)
        val area = w * h
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
    }

    private data class Track(
        val id: Int,
        var generation: Int,
        var rect: Rect,
        var imageAspect: Float,
        var evidence: Float,
        var observations: Int,
        var misses: Int,
        var lastOpportunity: Long,
        var lastSeenElapsedMs: Long,
        var state: CandidateEvidenceState,
        var submittedGeneration: Int = 0,
        var completedGeneration: Int = 0,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        var centerTravel: Float = 0f,
        var centerMoves: Int = 0,
        var minArea: Float,
        var maxArea: Float
    )

    private data class Match(val trackIndex: Int, val candidateIndex: Int, val score: Float)

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    @Synchronized
    fun reset() = tracks.clear()

    @Synchronized
    fun update(
        regions: List<RecoveryRegion>,
        width: Int,
        height: Int,
        opportunity: Long,
        elapsedMs: Long
    ): CandidateEvidenceUpdate {
        val events = mutableListOf<CandidateEvidenceEvent>()
        val rects = regions.map { it.normalized(width, height) }
        val edges = buildList {
            tracks.forEachIndexed { ti, track ->
                rects.forEachIndexed { ci, rect ->
                    association(track, rect).takeIf { it > 0f }?.let { add(Match(ti, ci, it)) }
                }
            }
        }.sortedByDescending(Match::score)
        val matchedTracks = mutableSetOf<Int>()
        val matchedCandidates = mutableSetOf<Int>()
        val assignments = mutableListOf<Match>()
        edges.forEach { edge ->
            if (edge.trackIndex !in matchedTracks && edge.candidateIndex !in matchedCandidates) {
                matchedTracks += edge.trackIndex
                matchedCandidates += edge.candidateIndex
                assignments += edge
            }
        }

        val bindingByCandidate = mutableMapOf<Int, CandidateEvidenceBinding>()
        assignments.forEach { edge ->
            val track = tracks[edge.trackIndex]
            val fresh = rects[edge.candidateIndex]
            val region = regions[edge.candidateIndex]
            val previousState = track.state
            val movement = distance(track.rect, fresh)
            val scale = sizeRatio(track.rect, fresh)
            val materialChange = movement > .12f || scale < .42f
            if (materialChange && previousState == CandidateEvidenceState.PROVISIONAL) {
                track.generation++
                track.evidence = 0f
                track.observations = 0
                track.submittedGeneration = 0
                track.completedGeneration = 0
            }
            val dx = fresh.cx - track.rect.cx
            val dy = fresh.cy - track.rect.cy
            track.velocityX = track.velocityX * .35f + dx * .65f
            track.velocityY = track.velocityY * .35f + dy * .65f
            track.centerTravel += movement
            track.centerMoves++
            track.rect = if (nested(track.rect, fresh) && fresh.area < track.rect.area * .72f) {
                recenter(track.rect, fresh.cx, fresh.cy)
            } else fresh
            track.imageAspect = width.toFloat() / height.coerceAtLeast(1)
            track.lastOpportunity = opportunity
            track.lastSeenElapsedMs = elapsedMs
            track.misses = 0
            track.observations++
            track.evidence = (track.evidence + hitEvidence).coerceAtMost(eligibleEvidence + 2f)
            track.minArea = min(track.minArea, fresh.area)
            track.maxArea = max(track.maxArea, fresh.area)
            track.state = when {
                track.submittedGeneration == track.generation -> CandidateEvidenceState.SUBMITTED
                track.observations >= 2 && track.evidence >= eligibleEvidence -> CandidateEvidenceState.ELIGIBLE
                else -> CandidateEvidenceState.PROVISIONAL
            }
            events += CandidateEvidenceEvent(
                "EVIDENCE_HIT", track.id, track.generation,
                "opportunity=$opportunity,evidence=${track.evidence},observations=${track.observations},state=${track.state}"
            )
            if (previousState != CandidateEvidenceState.ELIGIBLE && track.state == CandidateEvidenceState.ELIGIBLE) {
                events += CandidateEvidenceEvent("ELIGIBLE", track.id, track.generation, "evidence=${track.evidence}")
            } else if (previousState == CandidateEvidenceState.LOST_GRACE) {
                events += CandidateEvidenceEvent("REASSOCIATE", track.id, track.generation, "misses_recovered=1")
            }
            bindingByCandidate[edge.candidateIndex] = track.binding(edge.candidateIndex, region)
        }

        regions.forEachIndexed { index, region ->
            if (index in matchedCandidates) return@forEachIndexed
            val rect = rects[index]
            val track = Track(
                id = nextId++, generation = 1, rect = rect,
                imageAspect = width.toFloat() / height.coerceAtLeast(1),
                evidence = hitEvidence, observations = 1, misses = 0,
                lastOpportunity = opportunity, lastSeenElapsedMs = elapsedMs,
                state = CandidateEvidenceState.PROVISIONAL,
                minArea = rect.area, maxArea = rect.area
            )
            tracks += track
            events += CandidateEvidenceEvent("EVIDENCE_HIT", track.id, track.generation, "opportunity=$opportunity,evidence=$hitEvidence,observations=1,state=PROVISIONAL")
            bindingByCandidate[index] = track.binding(index, region)
        }

        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            if (track.lastOpportunity == opportunity) continue
            val previous = track.state
            track.misses++
            track.evidence = (track.evidence - missDecay).coerceAtLeast(0f)
            if (previous != CandidateEvidenceState.PROVISIONAL || track.submittedGeneration == track.generation) {
                track.state = CandidateEvidenceState.LOST_GRACE
            }
            events += CandidateEvidenceEvent(
                "EVIDENCE_DECAY", track.id, track.generation,
                "opportunity=$opportunity,evidence=${track.evidence},misses=${track.misses},state=${track.state}"
            )
            if (previous != CandidateEvidenceState.LOST_GRACE && track.state == CandidateEvidenceState.LOST_GRACE) {
                events += CandidateEvidenceEvent("LOST_GRACE", track.id, track.generation, "misses=${track.misses}")
            }
            val graceExpired = track.misses > graceOpportunities || elapsedMs - track.lastSeenElapsedMs > graceMs
            if (graceExpired && (track.evidence < retainEvidence || track.misses > graceOpportunities + 1)) {
                events += CandidateEvidenceEvent("LOST", track.id, track.generation, "lifetime=${opportunity - track.lastOpportunity},misses=${track.misses}")
                iterator.remove()
            }
        }

        val stable = tracks.filter {
            it.state == CandidateEvidenceState.ELIGIBLE || it.state == CandidateEvidenceState.SUBMITTED || it.state == CandidateEvidenceState.LOST_GRACE
        }.map { track ->
            DetectionBox(
                points = listOf(
                    NormalizedPoint(track.rect.l, track.rect.t), NormalizedPoint(track.rect.r, track.rect.t),
                    NormalizedPoint(track.rect.r, track.rect.b), NormalizedPoint(track.rect.l, track.rect.b)
                ),
                key = "evidence:${track.id}:${track.generation}",
                imageAspect = track.imageAspect,
                highlight = DetectionHighlight.POTENTIAL,
                stableCandidate = true,
                trackId = track.id,
                overlayAlpha = if (track.state == CandidateEvidenceState.LOST_GRACE) .52f else 1f
            )
        }
        return CandidateEvidenceUpdate(
            bindings = regions.indices.mapNotNull(bindingByCandidate::get),
            stableBoxes = suppressOverlappingPresentationBoxes(stable),
            events = events
        )
    }

    @Synchronized
    fun eligibleBindings(width: Int, height: Int): List<CandidateEvidenceBinding> = tracks.mapNotNull { track ->
        if (track.state != CandidateEvidenceState.ELIGIBLE && track.state != CandidateEvidenceState.SUBMITTED &&
            track.state != CandidateEvidenceState.LOST_GRACE
        ) return@mapNotNull null
        val region = track.rect.toRegion(width, height)
        track.binding(-1, region)
    }

    @Synchronized
    fun markSubmitted(identity: CandidateIdentity): CandidateEvidenceEvent? {
        val track = tracks.firstOrNull { it.id == identity.trackId && it.generation == identity.generation } ?: return null
        if (track.submittedGeneration == track.generation) {
            return CandidateEvidenceEvent("ALREADY_SUBMITTED", track.id, track.generation)
        }
        if (track.state != CandidateEvidenceState.ELIGIBLE && track.state != CandidateEvidenceState.LOST_GRACE) return null
        track.submittedGeneration = track.generation
        track.state = CandidateEvidenceState.SUBMITTED
        return CandidateEvidenceEvent("SUBMITTED", track.id, track.generation, "evidence=${track.evidence}")
    }

    @Synchronized
    fun markCompleted(identity: CandidateIdentity, success: Boolean): CandidateEvidenceEvent? {
        val track = tracks.firstOrNull { it.id == identity.trackId && it.generation == identity.generation } ?: return null
        track.completedGeneration = track.generation
        track.state = CandidateEvidenceState.SUBMITTED
        return CandidateEvidenceEvent("COMPLETED", track.id, track.generation, "success=${if (success) 1 else 0}")
    }

    private fun Track.binding(index: Int, region: RecoveryRegion) = CandidateEvidenceBinding(
        candidateIndex = index,
        trackId = id,
        generation = generation,
        state = state,
        evidence = evidence,
        observations = observations,
        misses = misses,
        alreadySubmitted = submittedGeneration == generation,
        centerJitter = if (centerMoves == 0) 0f else centerTravel / centerMoves,
        scaleDrift = if (maxArea <= 0f) 0f else 1f - minArea / maxArea,
        region = region
    )

    private fun RecoveryRegion.normalized(width: Int, height: Int) = Rect(
        (left / width.coerceAtLeast(1)).coerceIn(0f, 1f), (top / height.coerceAtLeast(1)).coerceIn(0f, 1f),
        (right / width.coerceAtLeast(1)).coerceIn(0f, 1f), (bottom / height.coerceAtLeast(1)).coerceIn(0f, 1f)
    )

    private fun Rect.toRegion(width: Int, height: Int) = RecoveryRegion(l * width, t * height, r * width, b * height)

    private fun association(track: Track, fresh: Rect): Float {
        val predicted = recenter(track.rect, track.rect.cx + track.velocityX, track.rect.cy + track.velocityY)
        val geometry = max(geometry(track.rect, fresh), geometry(predicted, fresh) + .12f)
        return if (geometry <= 0f) 0f else geometry + sizeRatio(track.rect, fresh) * .25f - track.misses * .18f
    }

    private fun geometry(a: Rect, b: Rect): Float {
        val overlap = iou(a, b)
        val d = distance(a, b)
        return when {
            overlap >= .12f -> 3f + overlap
            nested(a, b) && d <= maxCenterDistance * 1.2f -> 2.5f - d
            d <= maxCenterDistance && sizeRatio(a, b) >= .28f -> 1.5f - d
            else -> 0f
        }
    }

    private fun nested(a: Rect, b: Rect): Boolean = contains(a, b) || contains(b, a)
    private fun contains(outer: Rect, inner: Rect): Boolean =
        inner.area > 0f && intersection(outer, inner) / inner.area >= .76f

    private fun distance(a: Rect, b: Rect): Float {
        val dx = a.cx - b.cx; val dy = a.cy - b.cy
        return sqrt(dx * dx + dy * dy)
    }

    private fun sizeRatio(a: Rect, b: Rect): Float {
        val larger = max(a.area, b.area)
        return if (larger <= 0f) 0f else min(a.area, b.area) / larger
    }

    private fun iou(a: Rect, b: Rect): Float {
        val inter = intersection(a, b); val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun intersection(a: Rect, b: Rect): Float =
        max(0f, min(a.r, b.r) - max(a.l, b.l)) * max(0f, min(a.b, b.b) - max(a.t, b.t))

    private fun recenter(rect: Rect, cx: Float, cy: Float): Rect {
        val halfW = rect.w / 2f; val halfH = rect.h / 2f
        val left = (cx - halfW).coerceIn(0f, 1f - rect.w)
        val top = (cy - halfH).coerceIn(0f, 1f - rect.h)
        return Rect(left, top, left + rect.w, top + rect.h)
    }
}
