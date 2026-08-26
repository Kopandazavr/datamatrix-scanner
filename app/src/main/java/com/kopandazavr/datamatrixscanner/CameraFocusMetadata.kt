@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package com.kopandazavr.datamatrixscanner

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

internal data class FocusLensMetadata(
    val sensorTimestampNs: Long,
    val frameNumber: Long,
    val observedElapsedMs: Long,
    val actualDistance: Float?,
    val lensState: Int?,
    val afMode: Int?,
    val afState: Int?,
    val aeState: Int?,
    val exposureTimeNs: Long?,
    val iso: Int?,
    val physicalCameraId: String?,
    val cropRegion: String?,
    val afRegions: String?
) {
    val stationary: Boolean get() = lensState == CaptureResult.LENS_STATE_STATIONARY
}

internal data class FocusLensRequest(
    val sessionId: Long,
    val requestId: Long,
    val phase: String,
    val requestedDistance: Float,
    val sentElapsedMs: Long,
    val ackMs: Long? = null,
    val ackSuccess: Boolean? = null,
    val error: String? = null
)

/**
 * Read-only Camera2 telemetry bridge. The callback is installed on the CameraX ImageAnalysis
 * use-case, so no raw CameraDevice/CameraCaptureSession operation is performed here.
 */
internal class FocusLensMetadataMonitor(
    private val logger: PipelineDebugLogger?,
    private val statistics: ScannerStatisticsStore?
) {
    private val lock = Any()
    private val recent = ArrayDeque<FocusLensMetadata>(METADATA_RING_SIZE)
    private val latest = AtomicReference<FocusLensMetadata?>(null)

    @Volatile private var focusSessionId: Long? = null
    @Volatile private var logUntilElapsedMs: Long = 0L
    @Volatile private var currentRequest: FocusLensRequest? = null
    @Volatile private var lastLoggedDistance: Float? = null
    @Volatile private var lastLoggedLensState: Int? = null
    @Volatile private var lastLoggedAtMs: Long = 0L
    @Volatile private var appliedLoggedRequestId: Long? = null
    @Volatile private var stationaryLoggedRequestId: Long? = null

    val captureCallback: CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val now = SystemClock.elapsedRealtime()
            val physicalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            } else null
            val metadata = FocusLensMetadata(
                sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                frameNumber = result.frameNumber,
                observedElapsedMs = now,
                actualDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                lensState = result.get(CaptureResult.LENS_STATE),
                afMode = result.get(CaptureResult.CONTROL_AF_MODE),
                afState = result.get(CaptureResult.CONTROL_AF_STATE),
                aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                physicalCameraId = physicalId,
                cropRegion = result.get(CaptureResult.SCALER_CROP_REGION)?.toShortString(),
                afRegions = result.get(CaptureResult.CONTROL_AF_REGIONS)?.joinToString(";") { region ->
                    "${region.rect.toShortString()}@${region.meteringWeight}"
                }
            )
            latest.set(metadata)
            synchronized(lock) {
                if (recent.size >= METADATA_RING_SIZE) recent.removeFirst()
                recent.addLast(metadata)
            }
            if (!physicalId.isNullOrBlank()) statistics?.setPhysicalCameraId(physicalId)
            maybeLog(metadata, now)
        }
    }

    fun beginSession(sessionId: Long) {
        focusSessionId = sessionId
        logUntilElapsedMs = Long.MAX_VALUE
        currentRequest = null
        lastLoggedDistance = null
        lastLoggedLensState = null
        lastLoggedAtMs = 0L
        appliedLoggedRequestId = null
        stationaryLoggedRequestId = null
    }

    fun endSession() {
        logUntilElapsedMs = SystemClock.elapsedRealtime() + POST_FOCUS_METADATA_MS
    }

    fun latest(): FocusLensMetadata? = latest.get()

    fun closest(sensorTimestampNs: Long, toleranceNs: Long = METADATA_MATCH_TOLERANCE_NS): FocusLensMetadata? {
        if (sensorTimestampNs <= 0L) return latest.get()
        return synchronized(lock) {
            recent.minByOrNull { abs(it.sensorTimestampNs - sensorTimestampNs) }
                ?.takeIf { abs(it.sensorTimestampNs - sensorTimestampNs) <= toleranceNs }
        }
    }

    fun requestSent(sessionId: Long, requestId: Long, phase: String, distance: Float): FocusLensRequest {
        val request = FocusLensRequest(
            sessionId = sessionId,
            requestId = requestId,
            phase = phase,
            requestedDistance = distance,
            sentElapsedMs = SystemClock.elapsedRealtime()
        )
        currentRequest = request
        appliedLoggedRequestId = null
        stationaryLoggedRequestId = null
        logger?.log(
            "FOCUS_LENS_REQUEST",
            "focusSession" to sessionId,
            "requestId" to requestId,
            "event" to "sent",
            "phase" to phase,
            "requestedDistance" to distance,
            "sentElapsedMs" to request.sentElapsedMs
        )
        return request
    }

    fun requestAcknowledged(request: FocusLensRequest, success: Boolean, error: String?): FocusLensRequest {
        val completed = request.copy(
            ackMs = (SystemClock.elapsedRealtime() - request.sentElapsedMs).coerceAtLeast(0L),
            ackSuccess = success,
            error = error
        )
        if (currentRequest?.requestId == request.requestId) currentRequest = completed
        logger?.log(
            "FOCUS_LENS_REQUEST",
            "focusSession" to request.sessionId,
            "requestId" to request.requestId,
            "event" to "ack",
            "phase" to request.phase,
            "requestedDistance" to request.requestedDistance,
            "sentElapsedMs" to request.sentElapsedMs,
            "ackMs" to completed.ackMs,
            "success" to if (success) 1 else 0,
            "error" to error
        )
        return completed
    }

    private fun maybeLog(metadata: FocusLensMetadata, now: Long) {
        val sessionWindow = now <= logUntilElapsedMs
        if (!sessionWindow && logger?.isRecording != true) return
        val pending = currentRequest
        val actual = metadata.actualDistance
        val distanceChanged = when {
            actual == null -> false
            lastLoggedDistance == null -> true
            else -> abs(actual - requireNotNull(lastLoggedDistance)) >= LENS_CHANGE_LOG_THRESHOLD
        }
        val stateChanged = metadata.lensState != lastLoggedLensState
        val reached = pending?.let { request ->
            actual != null && abs(actual - request.requestedDistance) <= distanceTolerance(request.requestedDistance)
        } == true
        val firstApplied = reached && appliedLoggedRequestId != pending?.requestId
        val firstStationary = pending != null && metadata.stationary && stationaryLoggedRequestId != pending.requestId
        val rateDue = now - lastLoggedAtMs >= if (sessionWindow) METADATA_MAX_SILENCE_MS else IDLE_METADATA_MAX_SILENCE_MS
        if (!distanceChanged && !stateChanged && !firstApplied && !firstStationary && !rateDue) return

        if (firstApplied) appliedLoggedRequestId = pending?.requestId
        if (firstStationary) stationaryLoggedRequestId = pending?.requestId
        lastLoggedDistance = actual ?: lastLoggedDistance
        lastLoggedLensState = metadata.lensState
        lastLoggedAtMs = now
        logger?.log(
            if (sessionWindow) "FOCUS_LENS_APPLIED" else "FOCUS_CAPTURE_RESULT",
            "focusSession" to (pending?.sessionId ?: focusSessionId),
            "requestId" to pending?.requestId,
            "phase" to pending?.phase,
            "requestedDistance" to pending?.requestedDistance,
            "actualDistance" to actual,
            "lensState" to lensStateName(metadata.lensState),
            "afMode" to afModeName(metadata.afMode),
            "afState" to afStateName(metadata.afState),
            "aeState" to (metadata.aeState ?: -1),
            "reached" to if (reached) 1 else 0,
            "stationary" to if (metadata.stationary) 1 else 0,
            "frameNumber" to metadata.frameNumber,
            "sensorTimestamp" to metadata.sensorTimestampNs,
            "exposureNs" to metadata.exposureTimeNs,
            "iso" to metadata.iso,
            "physicalCamera" to metadata.physicalCameraId,
            "cropRegion" to metadata.cropRegion,
            "afRegions" to metadata.afRegions,
            "sinceRequestMs" to pending?.let { now - it.sentElapsedMs }
        )
    }

    companion object {
        fun distanceTolerance(requestedDistance: Float): Float = maxOf(.035f, requestedDistance * .012f)

        fun lensStateName(value: Int?): String = when (value) {
            CaptureResult.LENS_STATE_STATIONARY -> "STATIONARY"
            CaptureResult.LENS_STATE_MOVING -> "MOVING"
            null -> "UNKNOWN"
            else -> value.toString()
        }

        fun afModeName(value: Int?): String = when (value) {
            CaptureResult.CONTROL_AF_MODE_OFF -> "OFF"
            CaptureResult.CONTROL_AF_MODE_AUTO -> "AUTO"
            CaptureResult.CONTROL_AF_MODE_MACRO -> "MACRO"
            CaptureResult.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONT_VIDEO"
            CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONT_PICTURE"
            CaptureResult.CONTROL_AF_MODE_EDOF -> "EDOF"
            null -> "UNKNOWN"
            else -> value.toString()
        }

        fun afStateName(value: Int?): String = when (value) {
            CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
            null -> "UNKNOWN"
            else -> value.toString()
        }

        private const val METADATA_RING_SIZE = 96
        private const val POST_FOCUS_METADATA_MS = 2_000L
        private const val METADATA_MATCH_TOLERANCE_NS = 12_000_000L
        private const val LENS_CHANGE_LOG_THRESHOLD = .025f
        private const val METADATA_MAX_SILENCE_MS = 450L
        private const val IDLE_METADATA_MAX_SILENCE_MS = 650L
    }
}
