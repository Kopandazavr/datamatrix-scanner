package com.kopandazavr.datamatrixscanner

import kotlin.math.abs

internal enum class FocusOwner { IDLE, NATIVE_AF, MANUAL_SEARCH, MANUAL_HOLD, USER_HOLD, PARKING }

internal data class FocusControlSnapshot(
    val owner: FocusOwner = FocusOwner.IDLE,
    val requestedDistance: Float? = null,
    val actualDistance: Float? = null,
    val homeDistance: Float? = null,
    val minimumFocusDistance: Float? = null,
    val actualStale: Boolean = true,
    val generation: Long = 0L
) {
    val nativeAfActive: Boolean get() = owner == FocusOwner.NATIVE_AF
    val homeAvailable: Boolean get() = homeDistance != null
    val homeToggleEnabled: Boolean get() = !actualStale && actualDistance != null
    val normalizedRequested: Float?
        get() = if (owner == FocusOwner.MANUAL_SEARCH || owner == FocusOwner.MANUAL_HOLD ||
            owner == FocusOwner.USER_HOLD || owner == FocusOwner.PARKING
        ) normalizeLensDistance(requestedDistance, minimumFocusDistance) else null
    val normalizedActual: Float? get() = normalizeLensDistance(actualDistance, minimumFocusDistance)
    val normalizedHome: Float? get() = normalizeLensDistance(homeDistance, minimumFocusDistance)
}

internal data class HomeToggleResult(
    val generation: Long,
    val heldDistance: Float?,
    val homeDistance: Float?,
    val cleared: Boolean,
    val accepted: Boolean
)

internal class FocusOwnershipState {
    private var generation = 0L
    private var owner = FocusOwner.IDLE
    private var requestedDistance: Float? = null
    private var actualDistance: Float? = null
    private var homeDistance: Float? = null
    private var minimumFocusDistance: Float? = null
    private var actualStale = true

    fun setCapabilities(minimumDistance: Float?) {
        minimumFocusDistance = minimumDistance?.takeIf { it.isFinite() && it > 0f }
        requestedDistance = clamp(requestedDistance)
        actualDistance = clamp(actualDistance)
        homeDistance = clamp(homeDistance)
    }

    fun restoreHome(distance: Float?) { homeDistance = clamp(distance) }

    fun metadata(distance: Float?, stale: Boolean) {
        actualDistance = clamp(distance)
        actualStale = stale || distance == null
    }

    fun initializeHeld(distance: Float?): Pair<Long, Float?> {
        val token = take(if (distance == null) FocusOwner.IDLE else FocusOwner.USER_HOLD)
        requestedDistance = clamp(distance)
        return token to requestedDistance
    }

    fun beginManual(): Long = take(FocusOwner.MANUAL_SEARCH)
    fun beginNativeAf(): Long = take(FocusOwner.NATIVE_AF)

    fun userTarget(distance: Float): Pair<Long, Float?> {
        val token = take(FocusOwner.USER_HOLD)
        requestedDistance = clamp(distance)
        return token to requestedDistance
    }

    fun holdActual(): Pair<Long, Float?> {
        val target = clamp(actualDistance ?: requestedDistance)
        val token = take(if (target == null) FocusOwner.IDLE else FocusOwner.USER_HOLD)
        requestedDistance = target
        return token to target
    }

    fun toggleHome(): HomeToggleResult {
        val actual = actualDistance
        if (actualStale || actual == null) return HomeToggleResult(generation, null, homeDistance, false, false)
        val cleared = lensDistanceMatchesActual(homeDistance, actual)
        homeDistance = if (cleared) null else actual
        val (token, held) = holdActual()
        return HomeToggleResult(token, held, homeDistance, cleared, true)
    }

    fun beginParking(distance: Float): Pair<Long, Float?> {
        val token = take(FocusOwner.PARKING)
        requestedDistance = clamp(distance)
        return token to requestedDistance
    }

    fun completeParking(token: Long): Boolean {
        if (token != generation || owner != FocusOwner.PARKING) return false
        owner = if (requestedDistance == null) FocusOwner.IDLE else FocusOwner.USER_HOLD
        return true
    }

    fun requested(token: Long, distance: Float) {
        if (token == generation) requestedDistance = clamp(distance)
    }

    fun completeManual(token: Long, selectedDistance: Float?, success: Boolean): Boolean {
        if (token != generation || owner != FocusOwner.MANUAL_SEARCH) return false
        requestedDistance = clamp(selectedDistance ?: requestedDistance)
        owner = if (success && requestedDistance != null) FocusOwner.MANUAL_HOLD else FocusOwner.IDLE
        return true
    }

    fun completeNativeAf(token: Long): Boolean = token == generation && owner == FocusOwner.NATIVE_AF
    fun isCurrent(token: Long): Boolean = token == generation

    fun snapshot(): FocusControlSnapshot = FocusControlSnapshot(
        owner, requestedDistance, actualDistance, homeDistance,
        minimumFocusDistance, actualStale, generation
    )

    private fun take(nextOwner: FocusOwner): Long {
        generation++
        owner = nextOwner
        return generation
    }

    private fun clamp(value: Float?): Float? {
        if (value == null || !value.isFinite()) return null
        val maximum = minimumFocusDistance ?: return value.coerceAtLeast(0f)
        return value.coerceIn(0f, maximum)
    }
}

internal fun normalizeLensDistance(distance: Float?, minimumDistance: Float?): Float? {
    if (distance == null || minimumDistance == null || !distance.isFinite() || minimumDistance <= 0f) return null
    return (distance / minimumDistance).coerceIn(0f, 1f)
}

internal fun lensDistanceFromSlider(normalizedFromTop: Float, minimumDistance: Float?): Float? {
    if (minimumDistance == null || !minimumDistance.isFinite() || minimumDistance <= 0f) return null
    return minimumDistance * normalizedFromTop.coerceIn(0f, 1f)
}

internal fun lensDistanceMatchesActual(requested: Float?, actual: Float?): Boolean {
    if (requested == null || actual == null) return false
    return abs(requested - actual) <= maxOf(.035f, requested * .012f)
}
