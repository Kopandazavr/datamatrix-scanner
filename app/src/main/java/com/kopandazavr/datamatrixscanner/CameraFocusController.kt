@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package com.kopandazavr.datamatrixscanner

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.kopandazavr.datamatrixscanner.scanner.CenterSharpnessSnapshot
import com.kopandazavr.datamatrixscanner.scanner.DataMatrixAnalyzer
import com.kopandazavr.datamatrixscanner.scanner.FocusCandidateScore
import com.kopandazavr.datamatrixscanner.scanner.FocusCandidateWindowTracker
import com.kopandazavr.datamatrixscanner.scanner.MANUAL_FOCUS_SHARP_THRESHOLD
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal data class FocusSessionResult(
    val success: Boolean,
    val skippedAlreadySharp: Boolean,
    val sharpnessBefore: Float?,
    val sharpnessAfter: Float?,
    val selectedDistance: Float? = null,
    val stableCandidates: Int = 0,
    val lensSteps: Int = 0,
    val timedOut: Boolean = false,
    val apparentArea: Float? = null,
    val noCandidateFramesBefore: Int = 0,
    val reacquireFrames: Int? = null,
    val reacquireMs: Long? = null,
    val cancelled: Boolean = false
)

internal data class CameraFocusController(
    val busy: Boolean,
    val nativeAfActive: Boolean,
    val manualMode: ManualFocusMode,
    val nominalProgressMs: Float,
    val control: FocusControlSnapshot,
    val onTap: () -> Unit,
    val onLongPress: () -> Unit,
    val onManualModeChange: (ManualFocusMode) -> Unit,
    val onHomeToggle: () -> Unit,
    val onSliderTarget: (Float) -> Unit
)

private enum class FocusRequestKind { MANUAL, AUTO }

private data class FocusRequest(
    val kind: FocusRequestKind,
    val mode: ManualFocusMode,
    val reason: String,
    val ownershipGeneration: Long,
    val completion: CompletableDeferred<FocusSessionResult>? = null
)

private data class ManualSweepResult(
    val applied: Boolean,
    val success: Boolean,
    val skippedAlreadySharp: Boolean,
    val distance: Float?,
    val score: FocusCandidateScore?,
    val finalScore: FocusCandidateScore?,
    val lensCommands: Int,
    val timedOut: Boolean,
    val cancelled: Boolean = false
)

private data class FocusMeasurement(
    val candidateScore: FocusCandidateScore,
    val sharpness: CenterSharpnessSnapshot?,
    val metadata: FocusLensMetadata?
)

private data class LensCommandOutcome(
    val request: FocusLensRequest,
    val acknowledged: Boolean,
    val appliedMetadata: FocusLensMetadata?,
    val usedMetadataFallback: Boolean,
    val error: String?
)

private const val FocusPreferencesName = "camera_focus_preferences"
private const val ManualFocusModePreference = "manual_focus_mode"
private const val LastManualFocusDistancePreference = "manual_focus_last_distance"
private const val HomeFocusDistancePreferencePrefix = "focus_home_"
private const val LastPhysicalCameraPreferencePrefix = "focus_last_physical_"

private fun homeStorageKey(logicalCameraId: String, physicalCameraId: String?): String {
    val identity = listOfNotNull(logicalCameraId, physicalCameraId?.takeIf(String::isNotBlank)).joinToString("_")
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")
    return HomeFocusDistancePreferencePrefix + identity
}

@Composable
internal fun rememberCameraFocusController(
    active: Boolean,
    camera: Camera?,
    analyzer: DataMatrixAnalyzer,
    metadata: FocusLensMetadataMonitor,
    statistics: ScannerStatisticsStore,
    logger: PipelineDebugLogger? = null
): CameraFocusController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { context.getSharedPreferences(FocusPreferencesName, Context.MODE_PRIVATE) }
    val focusHaptics = remember(context) { FocusSessionHaptics(context) }
    val requests = remember { Channel<FocusRequest>(capacity = Channel.UNLIMITED) }
    val requestGate = remember { FocusRequestGate() }
    val nextFocusSession = remember { AtomicLong(0L) }
    val ownership = remember { FocusOwnershipState() }
    val directFocusMutex = remember { Mutex() }
    var busy by remember { mutableStateOf(false) }
    var uiBusy by remember { mutableStateOf(false) }
    var gateTransferPending by remember { mutableStateOf(false) }
    var runningKind by remember { mutableStateOf<FocusRequestKind?>(null) }
    var manualMode by remember {
        mutableStateOf(ManualFocusMode.fromPreference(preferences.getString(ManualFocusModePreference, null)))
    }
    var nominalProgressMs by remember { mutableFloatStateOf(manualMode.nominalProgressMs) }
    var controlSnapshot by remember { mutableStateOf(ownership.snapshot()) }
    var lastRequestedDistance by remember { mutableStateOf<Float?>(null) }
    var capabilitiesLoggedForCamera by remember { mutableStateOf<String?>(null) }
    var homePreferenceKey by remember { mutableStateOf<String?>(null) }
    var logicalCameraId by remember { mutableStateOf<String?>(null) }
    var lastActualMarkerLogged by remember { mutableStateOf<Float?>(null) }
    var lastSuccessfulDistance by remember {
        mutableStateOf(
            preferences.takeIf { it.contains(LastManualFocusDistancePreference) }
                ?.getFloat(LastManualFocusDistancePreference, Float.NaN)
                ?.takeIf { it.isFinite() && it >= 0f }
        )
    }

    LaunchedEffect(active, camera) {
        while (active && camera != null) {
            val latest = metadata.latest()
            ownership.metadata(
                distance = latest?.actualDistance,
                stale = latest == null || SystemClock.elapsedRealtime() - latest.observedElapsedMs > FOCUS_METADATA_STALE_MS
            )
            controlSnapshot = ownership.snapshot()
            val actual = latest?.actualDistance
            val physicalId = latest?.physicalCameraId
            val logicalId = logicalCameraId
            if (!physicalId.isNullOrBlank() && logicalId != null) {
                val physicalKey = homeStorageKey(logicalId, physicalId)
                if (physicalKey != homePreferenceKey) {
                    preferences.edit().putString(LastPhysicalCameraPreferencePrefix + logicalId, physicalId).apply()
                    homePreferenceKey = physicalKey
                    val physicalHome = physicalKey.takeIf(preferences::contains)
                        ?.let { preferences.getFloat(it, Float.NaN) }
                        ?.takeIf { it.isFinite() && it >= 0f }
                    ownership.restoreHome(physicalHome)
                    controlSnapshot = ownership.snapshot()
                    logger?.log("FOCUS_HOME_SCOPE", "cameraId" to logicalId, "physicalCameraId" to physicalId, "homeDistance" to physicalHome)
                }
            }
            if (actual != null && (lastActualMarkerLogged == null || abs(actual - requireNotNull(lastActualMarkerLogged)) >= .02f)) {
                logger?.log(
                    "UI_ACTUAL_MARKER", "actualDistance" to actual,
                    "normalized" to controlSnapshot.normalizedActual,
                    "stale" to if (controlSnapshot.actualStale) 1 else 0,
                    "frame" to latest.frameNumber, "source" to "CaptureResult"
                )
                lastActualMarkerLogged = actual
            }
            delay(FOCUS_CONTROL_REFRESH_MS)
        }
    }

    LaunchedEffect(active, camera) {
        if (!active || camera == null) return@LaunchedEffect
        var parkedThisIdleEpisode = false
        while (true) {
            val snapshot = ownership.snapshot()
            val signal = analyzer.nativeAfSignal()
            val now = SystemClock.elapsedRealtime()
            if (snapshot.owner != FocusOwner.IDLE) {
                parkedThisIdleEpisode = false
            } else if (!parkedThisIdleEpisode && !busy &&
                now - signal.candidateElapsedMs >= IDLE_PARK_NO_CANDIDATE_MS &&
                now - signal.centerChangeElapsedMs >= IDLE_PARK_STABLE_SCENE_MS
            ) {
                val target = snapshot.homeDistance
                    ?: lastSuccessfulDistance
                    ?: statistics.learnedFocusRecommendation().anchor
                    ?: snapshot.minimumFocusDistance?.times(.5f)
                if (target != null) {
                    val (token, distance) = ownership.beginParking(target)
                    controlSnapshot = ownership.snapshot()
                    parkedThisIdleEpisode = true
                    logger?.log(
                        "FOCUS_PARK", "event" to "start", "token" to token,
                        "distance" to distance, "source" to if (snapshot.homeDistance != null) "HOME" else "fallback"
                    )
                    if (distance != null) scope.launch {
                        directFocusMutex.withLock {
                            if (ownership.isCurrent(token)) {
                                val applied = applyHeldLensDistance(context, camera, metadata, distance, logger, "idle_park")
                                val accepted = ownership.completeParking(token)
                                controlSnapshot = ownership.snapshot()
                                logger?.log(
                                    "FOCUS_PARK", "event" to "end", "token" to token,
                                    "applied" to if (applied) 1 else 0, "accepted" to if (accepted) 1 else 0
                                )
                            }
                        }
                    }
                }
            }
            delay(IDLE_PARK_WATCH_INTERVAL_MS)
        }
    }

    LaunchedEffect(active, camera) {
        if (!active || camera == null) return@LaunchedEffect
        val capabilities = readCameraFocusCapabilities(camera)
        logicalCameraId = capabilities.cameraId
        ownership.setCapabilities(capabilities.minimumFocusDistance)
        val lastPhysicalId = metadata.latest()?.physicalCameraId
            ?: preferences.getString(LastPhysicalCameraPreferencePrefix + capabilities.cameraId, null)
        homePreferenceKey = homeStorageKey(capabilities.cameraId, lastPhysicalId)
        val savedHome = homePreferenceKey?.takeIf(preferences::contains)
            ?.let { preferences.getFloat(it, Float.NaN) }
            ?.takeIf { it.isFinite() && it >= 0f }
        ownership.restoreHome(savedHome)
        controlSnapshot = ownership.snapshot()
        statistics.setCameraContext(
            cameraId = capabilities.cameraId,
            hardwareLevel = capabilities.hardwareLevel,
            minimumFocusDistance = capabilities.minimumFocusDistance,
            focusCalibration = capabilities.focusCalibration,
            afModes = capabilities.afModes,
            maxRegionsAf = capabilities.maxRegionsAf
        )
        val initialDistance = savedHome
            ?: ownership.snapshot().requestedDistance
            ?: lastSuccessfulDistance
            ?: statistics.learnedFocusRecommendation().anchor
            ?: capabilities.minimumFocusDistance?.times(.5f)
        val (initialToken, heldInitial) = ownership.initializeHeld(initialDistance)
        controlSnapshot = ownership.snapshot()
        if (heldInitial != null) {
            applyHeldLensDistance(context, camera, metadata, heldInitial, logger, "camera_bind_initial")
        } else {
            disableDeviceAutoFocus(context, camera)
        }
        logger?.log(
            "FOCUS_BIND_OFF", "cameraId" to capabilities.cameraId, "token" to initialToken,
            "initialDistance" to heldInitial, "source" to when (heldInitial) {
                savedHome -> "HOME"
                lastSuccessfulDistance -> "LAST_HELD"
                else -> "SAFE_DEFAULT"
            }
        )

        for (request in requests) {
            val focusSessionId = nextFocusSession.incrementAndGet()
            nominalProgressMs = if (request.kind == FocusRequestKind.AUTO) AUTO_NOMINAL_PROGRESS_MS else request.mode.nominalProgressMs
            busy = true
            runningKind = request.kind
            val useFocusStartHaptic = request.reason == "manual_tap" || request.reason == "manual_long_press"
            // A long press enables persistent native AF. One activation pulse is enough: an
            // additional pulse when the initial AF attempt finishes is misleading because the
            // blue native-AF mode remains enabled. Manual tap keeps start + finish feedback.
            val useFocusEndHaptic = request.reason == "manual_tap"
            uiBusy = useFocusStartHaptic
            metadata.beginSession(focusSessionId)
            logger?.log("FOCUS_BUSY", "focusSession" to focusSessionId, "state" to 1, "kind" to request.kind.name)
            logger?.log("FOCUS_HAPTIC", "focusSession" to focusSessionId, "event" to "start", "performed" to if (useFocusStartHaptic) 1 else 0)
            if (useFocusStartHaptic) focusHaptics.strongPulse()
            logger?.log(
                "FOCUS_REQUEST",
                "focusSession" to focusSessionId,
                "reason" to request.reason,
                "kind" to request.kind.name,
                "mode" to request.mode.name,
                "strategy" to if (request.kind == FocusRequestKind.AUTO) "native_center_metering" else "metadata_hill_climb"
            )
            if (capabilitiesLoggedForCamera != capabilities.cameraId) {
                logCameraCapabilities(logger, focusSessionId, capabilities)
                capabilitiesLoggedForCamera = capabilities.cameraId
            }

            val startedAt = SystemClock.elapsedRealtime()
            val startActual = metadata.latest()?.actualDistance
            val result = try {
                when (request.kind) {
                    FocusRequestKind.MANUAL -> runManualCandidateFocusSession(
                        context = context,
                        camera = camera,
                        analyzer = analyzer,
                        metadata = metadata,
                        mode = request.mode,
                        logger = logger,
                        reason = request.reason,
                        focusSessionId = focusSessionId,
                        lastRequestedDistance = lastRequestedDistance,
                        savedSuccessfulDistance = lastSuccessfulDistance,
                        homeDistance = ownership.snapshot().homeDistance,
                        statistics = statistics,
                        isCurrent = { ownership.isCurrent(request.ownershipGeneration) },
                        onLensRequested = {
                            lastRequestedDistance = it
                            ownership.requested(request.ownershipGeneration, it)
                            controlSnapshot = ownership.snapshot()
                        }
                    )
                    FocusRequestKind.AUTO -> runNativeAutoFocusSession(
                        context = context,
                        camera = camera,
                        analyzer = analyzer,
                        metadata = metadata,
                        statistics = statistics,
                        logger = logger,
                        focusSessionId = focusSessionId,
                        reason = request.reason,
                        homeDistance = ownership.snapshot().homeDistance,
                        savedSuccessfulDistance = lastSuccessfulDistance,
                        learnedAnchorDistance = statistics.learnedFocusRecommendation().anchor,
                        lastRequestedDistance = lastRequestedDistance,
                        isCurrent = { ownership.isCurrent(request.ownershipGeneration) }
                    )
                }
            } catch (t: Throwable) {
                analyzer.setFocusSamplingActive(false)
                logger?.error(
                    "focus_session_exception",
                    "focusSession" to focusSessionId,
                    "reason" to request.reason,
                    "error" to t.javaClass.simpleName
                )
                FocusSessionResult(
                    success = false,
                    skippedAlreadySharp = false,
                    sharpnessBefore = analyzer.currentCenterSharpness(),
                    sharpnessAfter = analyzer.currentCenterSharpness(),
                    selectedDistance = metadata.latest()?.actualDistance,
                    timedOut = false
                )
            }

            val duration = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            val acceptedOwnership = when (request.kind) {
                FocusRequestKind.MANUAL -> ownership.completeManual(
                    request.ownershipGeneration,
                    result.selectedDistance,
                    result.success && !result.cancelled
                )
                FocusRequestKind.AUTO -> ownership.completeNativeAf(request.ownershipGeneration)
            }
            controlSnapshot = ownership.snapshot()
            if (acceptedOwnership && result.selectedDistance != null) {
                // selectedDistance is the final physical target. On a rejected native AF it is the
                // successfully restored rollback target, not the bad CameraX lock position.
                lastRequestedDistance = result.selectedDistance
                ownership.requested(request.ownershipGeneration, result.selectedDistance)
                controlSnapshot = ownership.snapshot()
                if (result.success) {
                    lastSuccessfulDistance = result.selectedDistance
                    preferences.edit().putFloat(LastManualFocusDistancePreference, result.selectedDistance).apply()
                }
            }
            if (!result.cancelled) statistics.recordFocus(
                FocusStatisticsEvent(
                    kind = if (request.kind == FocusRequestKind.AUTO) StatisticsFocusKind.AUTO else StatisticsFocusKind.MANUAL,
                    success = result.success,
                    timeout = result.timedOut,
                    durationMs = duration,
                    lensSteps = result.lensSteps,
                    startDistance = startActual,
                    endDistance = result.selectedDistance,
                    targetSharpness = result.sharpnessAfter,
                    apparentArea = result.apparentArea,
                    noCandidateFramesBefore = result.noCandidateFramesBefore,
                    reacquireFrames = result.reacquireFrames,
                    reacquireMs = result.reacquireMs
                )
            )
            metadata.endSession()
            logger?.log("FOCUS_HAPTIC", "focusSession" to focusSessionId, "event" to "end", "performed" to if (useFocusEndHaptic) 1 else 0)
            if (useFocusEndHaptic) focusHaptics.strongPulse()
            busy = false
            uiBusy = false
            runningKind = null
            logger?.log(
                "FOCUS_BUSY",
                "focusSession" to focusSessionId,
                "state" to 0,
                "durationMs" to duration,
                "success" to if (result.success) 1 else 0
            )
            if (gateTransferPending) gateTransferPending = false else requestGate.release()
            request.completion?.complete(result)
        }
    }

    fun enqueue(
        kind: FocusRequestKind,
        mode: ManualFocusMode,
        reason: String,
        persistentNativeMode: Boolean = false,
        completion: CompletableDeferred<FocusSessionResult>? = null
    ): Boolean {
        if (!active || camera == null) return false
        val gateAcquired = requestGate.tryAcquire()
        val transfersRunningAuto = !gateAcquired && kind == FocusRequestKind.MANUAL &&
            runningKind == FocusRequestKind.AUTO && busy && !gateTransferPending
        if (!gateAcquired && !transfersRunningAuto) return false
        val generation = if (kind == FocusRequestKind.MANUAL) ownership.beginManual() else ownership.beginNativeAf()
        controlSnapshot = ownership.snapshot()
        if (transfersRunningAuto) {
            gateTransferPending = true
            uiBusy = true
        }
        val request = FocusRequest(kind, mode, reason, generation, completion)
        val sent = requests.trySend(request).isSuccess
        if (!sent) {
            if (gateAcquired) requestGate.release()
            if (transfersRunningAuto) gateTransferPending = false
            if (kind == FocusRequestKind.MANUAL) ownership.completeManual(generation, null, false)
            else ownership.completeNativeAf(generation)
            controlSnapshot = ownership.snapshot()
        } else if (transfersRunningAuto) {
            scope.launch { runCatching { camera.cameraControl.cancelFocusAndMetering() } }
        }
        return sent
    }

    LaunchedEffect(active, camera) {
        if (!active || camera == null) return@LaunchedEffect
        var handledCandidate: String? = null
        var handledSceneGeneration = analyzer.nativeAfSignal().centerChangeGeneration
        var lastAttemptElapsed = Long.MIN_VALUE
        while (true) {
            val signal = analyzer.nativeAfSignal()
            val native = ownership.snapshot().nativeAfActive
            val now = SystemClock.elapsedRealtime()
            val newCandidate = signal.centralCandidateSignature?.takeIf {
                it != handledCandidate && now - signal.candidateElapsedMs <= NATIVE_AF_SIGNAL_FRESH_MS
            }
            val changedBlurryScene = signal.centerChangeGeneration != handledSceneGeneration && !signal.centerSharp
            if (native && !busy && now - lastAttemptElapsed >= NATIVE_AF_SUPERVISION_COOLDOWN_MS &&
                (newCandidate != null || changedBlurryScene)
            ) {
                val trigger = if (newCandidate != null) "candidate_new_or_changed" else "center_scene_changed_blurry"
                if (enqueue(FocusRequestKind.AUTO, manualMode, "supervised_$trigger", persistentNativeMode = true)) {
                    handledCandidate = newCandidate ?: handledCandidate
                    handledSceneGeneration = signal.centerChangeGeneration
                    lastAttemptElapsed = now
                    logger?.log(
                        "FOCUS_AUTO_TRIGGER", "reason" to trigger,
                        "candidate" to newCandidate, "sceneGeneration" to signal.centerChangeGeneration,
                        "sharpness" to signal.sharpness
                    )
                }
            }
            delay(NATIVE_AF_WATCH_INTERVAL_MS)
        }
    }

    return CameraFocusController(
        busy = uiBusy,
        nativeAfActive = controlSnapshot.nativeAfActive,
        manualMode = manualMode,
        nominalProgressMs = nominalProgressMs,
        control = controlSnapshot,
        onTap = {
            logger?.log("UI_EVENT", "action" to "focus_tap", "strategy" to "metadata_hill_climb")
            enqueue(FocusRequestKind.MANUAL, manualMode, "manual_tap")
        },
        onLongPress = {
            logger?.log("UI_EVENT", "action" to "focus_long_press", "strategy" to "native_center_metering")
            enqueue(FocusRequestKind.AUTO, manualMode, "manual_long_press", persistentNativeMode = true)
        },
        onManualModeChange = { mode ->
            manualMode = mode
            if (!busy) nominalProgressMs = mode.nominalProgressMs
            preferences.edit().putString(ManualFocusModePreference, mode.name).apply()
            logger?.log("UI_EVENT", "action" to "focus_mode", "value" to mode.name)
        },
        onHomeToggle = {
            uiBusy = false
            val result = ownership.toggleHome()
            if (result.accepted) {
                val key = homePreferenceKey
                if (key != null) {
                    if (result.homeDistance == null) preferences.edit().remove(key).apply()
                    else preferences.edit().putFloat(key, result.homeDistance).apply()
                }
                controlSnapshot = ownership.snapshot()
                logger?.log(
                    "FOCUS_HOME", "action" to if (result.cleared) "clear" else "set_or_move",
                    "homeDistance" to result.homeDistance, "heldDistance" to result.heldDistance,
                    "nativeCancelled" to 1
                )
                if (result.heldDistance != null && camera != null) scope.launch {
                    directFocusMutex.withLock {
                        if (ownership.isCurrent(result.generation)) {
                            applyHeldLensDistance(context, camera, metadata, result.heldDistance, logger, "home_hold")
                        }
                    }
                }
            } else {
                logger?.log("FOCUS_HOME", "action" to "ignored", "reason" to "actual_stale_or_missing")
            }
        },
        onSliderTarget = { distance ->
            val (token, target) = ownership.userTarget(distance)
            controlSnapshot = ownership.snapshot()
            uiBusy = false
            if (target != null && camera != null) scope.launch {
                directFocusMutex.withLock {
                    if (ownership.isCurrent(token)) {
                        lastRequestedDistance = target
                        applyHeldLensDistance(context, camera, metadata, target, logger, "user_slider")
                    }
                }
            }
        }
    )
}

private suspend fun runManualCandidateFocusSession(
    context: Context,
    camera: Camera,
    analyzer: DataMatrixAnalyzer,
    metadata: FocusLensMetadataMonitor,
    mode: ManualFocusMode,
    logger: PipelineDebugLogger?,
    reason: String,
    focusSessionId: Long,
    lastRequestedDistance: Float?,
    savedSuccessfulDistance: Float?,
    homeDistance: Float?,
    statistics: ScannerStatisticsStore,
    isCurrent: () -> Boolean,
    onLensRequested: (Float) -> Unit
): FocusSessionResult {
    val before = analyzer.currentCenterSharpness()
    logger?.log(
        "FOCUS_START",
        "focusSession" to focusSessionId,
        "reason" to reason,
        "mode" to mode.name,
        "strategy" to "metadata_hill_climb",
        "targetSharpBefore" to before,
        "startActual" to metadata.latest()?.actualDistance,
        "lastRequested" to lastRequestedDistance,
        "savedFallback" to savedSuccessfulDistance
    )
    analyzer.onCenterFocusStarted()
    analyzer.setFocusSamplingActive(true)
    awaitFutureOutcome(camera.cameraControl.cancelFocusAndMetering(), context, CANCEL_AF_TIMEOUT_MS)
    return try {
        val sweep = runManualFocusSweep(
            context = context,
            camera = camera,
            analyzer = analyzer,
            metadata = metadata,
            logger = logger,
            reason = reason,
            mode = mode,
            focusSessionId = focusSessionId,
            lastRequestedDistance = lastRequestedDistance,
            savedSuccessfulDistance = savedSuccessfulDistance,
            homeDistance = homeDistance,
            learnedAnchorDistance = statistics.learnedFocusRecommendation().anchor,
            isCurrent = isCurrent,
            onLensRequested = onLensRequested
        )
        if (!sweep.cancelled) analyzer.onCenterFocusCompleted(sweep.success)
        FocusSessionResult(
            success = sweep.success,
            skippedAlreadySharp = sweep.skippedAlreadySharp,
            sharpnessBefore = before,
            sharpnessAfter = analyzer.currentCenterSharpness(),
            selectedDistance = sweep.distance,
            stableCandidates = sweep.finalScore?.stableCount ?: sweep.score?.stableCount ?: 0,
            lensSteps = sweep.lensCommands,
            timedOut = sweep.timedOut,
            apparentArea = sweep.finalScore?.apparentArea ?: sweep.score?.apparentArea,
            reacquireFrames = sweep.finalScore?.frameCount,
            cancelled = sweep.cancelled
        ).also { result ->
            logger?.log(
                "FOCUS_END",
                "focusSession" to focusSessionId,
                "success" to if (result.success) 1 else 0,
                "path" to "metadata_hill_climb",
                "selectedDistance" to result.selectedDistance,
                "actualDistance" to metadata.latest()?.actualDistance,
                "stable" to result.stableCandidates,
                "lensCommands" to result.lensSteps,
                "timedOut" to if (result.timedOut) 1 else 0,
                "targetSharpBefore" to before,
                "targetSharpAfter" to result.sharpnessAfter
            )
        }
    } finally {
        analyzer.setFocusSamplingActive(false)
    }
}

private suspend fun runManualFocusSweep(
    context: Context,
    camera: Camera,
    analyzer: DataMatrixAnalyzer,
    metadata: FocusLensMetadataMonitor,
    logger: PipelineDebugLogger?,
    reason: String,
    mode: ManualFocusMode,
    focusSessionId: Long,
    lastRequestedDistance: Float?,
    savedSuccessfulDistance: Float?,
    homeDistance: Float?,
    learnedAnchorDistance: Float?,
    isCurrent: () -> Boolean,
    onLensRequested: (Float) -> Unit
): ManualSweepResult {
    val camera2Info = runCatching { Camera2CameraInfo.from(camera.cameraInfo) }.getOrNull()
        ?: return ManualSweepResult(false, false, false, null, null, null, 0, false)
    val minimumFocusDistance = runCatching {
        camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
    }.getOrNull() ?: return ManualSweepResult(false, false, false, null, null, null, 0, false)
    if (minimumFocusDistance <= 0f) {
        logger?.log("FOCUS_SWEEP_SKIP", "focusSession" to focusSessionId, "reason" to "manual_focus_unsupported", "request" to reason)
        return ManualSweepResult(false, false, false, null, null, null, 0, false)
    }
    val camera2Control = runCatching { Camera2CameraControl.from(camera.cameraControl) }.getOrNull()
        ?: return ManualSweepResult(false, false, false, null, null, null, 0, false)

    val startMetadata = metadata.latest()
    val startActual = startMetadata?.actualDistance?.coerceIn(0f, minimumFocusDistance)
    val startRequested = lastRequestedDistance?.coerceIn(0f, minimumFocusDistance)
    val savedFallback = (homeDistance ?: savedSuccessfulDistance ?: learnedAnchorDistance)
        ?.coerceIn(0f, minimumFocusDistance)
    val searchStartedAt = SystemClock.elapsedRealtime()
    val searchDeadline = searchStartedAt + if (mode == ManualFocusMode.PRECISE) PRECISE_SEARCH_BUDGET_MS else FAST_SEARCH_BUDGET_MS
    var timedOut = false
    var earlyConfirmed = false
    var finalizeReason = FocusFinalizeReason.SEARCH_COMPLETE
    var nextRequestId = 0L
    var cancelled = !isCurrent()

    logger?.log(
        "FOCUS_SWEEP_START",
        "focusSession" to focusSessionId,
        "reason" to reason,
        "mode" to mode.name,
        "minDistance" to minimumFocusDistance,
        "startActual" to startActual,
        "lastRequested" to startRequested,
        "savedFallback" to savedFallback,
        "homeDistance" to homeDistance,
        "learnedAnchor" to learnedAnchorDistance,
        "searchBudgetMs" to (searchDeadline - searchStartedAt)
    )

    analyzer.setFocusCandidateSamplingStrict(true)
    val currentMeasurement = awaitFocusMeasurement(
        analyzer = analyzer,
        metadata = metadata,
        notBeforeSensorTimestampNs = startMetadata?.sensorTimestampNs ?: 0L,
        timeoutMs = FOCUS_PRECHECK_TIMEOUT_MS,
        requiredCandidateFrames = FOCUS_PRECHECK_FRAMES,
        candidateGraceMs = 0L
    )
    analyzer.setFocusCandidateSamplingStrict(false)
    val currentSample = currentMeasurement.toSample(
        distance = startActual ?: Float.NaN,
        requestedDistance = startRequested,
        requestId = null,
        commandAckMs = null,
        settleMs = null
    )
    val state = ManualFocusStateMachine(startActual, startRequested, savedFallback, currentSample)
    logSweepSample(logger, focusSessionId, "current", currentSample)

    if (cancelled || !isCurrent()) {
        return ManualSweepResult(false, false, false, metadata.latest()?.actualDistance, null, null, 0, false, true)
    }

    if (shouldKeepCurrentFocus(currentSample.candidateScore, currentSample.targetSharpness)) {
        logger?.log(
            "FOCUS_EARLY_KEEP_CURRENT",
            "focusSession" to focusSessionId,
            "reason" to reason,
            "actualDistance" to startActual,
            "requestedDistance" to startRequested,
            "stable" to currentSample.candidateScore.stableCount,
            "decodedStable" to currentSample.candidateScore.stableDecodedCount,
            "targetSharp" to currentSample.targetSharpness,
            "coreSharp" to currentSample.targetCoreSharpness,
            "contextSharp" to currentSample.targetContextSharpness
        )
        logSweepEnd(logger, focusSessionId, true, startActual, currentMeasurement, currentSample, 1, "keep_current", 0, false)
        return ManualSweepResult(true, true, true, startActual, currentSample.candidateScore, currentSample.candidateScore, 0, false)
    }

    suspend fun probe(distance: Float, phase: String): FocusSweepSample? {
        if (!isCurrent()) {
            cancelled = true
            return null
        }
        if (SystemClock.elapsedRealtime() >= searchDeadline) {
            timedOut = true
            finalizeReason = FocusFinalizeReason.SEARCH_DEADLINE
            return null
        }
        val requestId = ++nextRequestId
        state.commandSent()
        onLensRequested(distance)
        val command = executeLensCommand(
            context = context,
            camera2Control = camera2Control,
            metadata = metadata,
            focusSessionId = focusSessionId,
            requestId = requestId,
            phase = phase,
            distance = distance
        )
        if (!isCurrent()) {
            cancelled = true
            return null
        }
        if (!command.acknowledged) {
            state.commandFailed()
            finalizeReason = FocusFinalizeReason.COMMAND_FAILURE
            return null
        }
        val notBeforeSensor = command.appliedMetadata?.sensorTimestampNs
            ?: metadata.latest()?.sensorTimestampNs
            ?: 0L
        val measurement = awaitFocusMeasurement(
            analyzer = analyzer,
            metadata = metadata,
            notBeforeSensorTimestampNs = notBeforeSensor,
            timeoutMs = minOf(FOCUS_PROBE_TIMEOUT_MS, (searchDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(40L)),
            requiredCandidateFrames = FOCUS_PROBE_FRAMES,
            candidateGraceMs = FOCUS_PROBE_CANDIDATE_GRACE_MS
        )
        val measuredAt = measurement.metadata?.observedElapsedMs ?: SystemClock.elapsedRealtime()
        return measurement.toSample(
            distance = distance,
            requestedDistance = distance,
            requestId = requestId,
            commandAckMs = command.request.ackMs,
            settleMs = (measuredAt - command.request.sentElapsedMs).coerceAtLeast(0L),
            metadataFallback = command.usedMetadataFallback
        )
    }

    val coarseStep = minimumFocusDistance / mode.coarseSegments.coerceAtLeast(1)
    val anchorDistance = selectManualSearchAnchor(
        homeDistance = homeDistance,
        savedSuccessfulDistance = savedSuccessfulDistance,
        learnedAnchorDistance = learnedAnchorDistance,
        lastRequestedDistance = startRequested,
        startActual = startActual,
        minimumFocusDistance = minimumFocusDistance
    )
    val anchor = if (startActual != null && lensDistanceMatchesActual(anchorDistance, startActual)) {
        currentSample
    } else {
        probe(anchorDistance, "anchor")?.also {
            state.observe(it)
            logSweepSample(logger, focusSessionId, "anchor", it)
        } ?: currentSample
    }
    if (anchor !== currentSample && shouldConfirmFocusEarly(anchor, currentSample)) {
        earlyConfirmed = true
        finalizeReason = FocusFinalizeReason.EARLY_CONFIRMED
    }

    suspend fun exploreDirection(direction: Float, phase: String, maxSteps: Int): Boolean {
        var cursor = anchorDistance
        var degradationRun = 0
        var improved = false
        repeat(maxSteps) {
            if (!isCurrent()) {
                cancelled = true
                return improved
            }
            if (earlyConfirmed || timedOut || SystemClock.elapsedRealtime() >= searchDeadline) {
                if (SystemClock.elapsedRealtime() >= searchDeadline) {
                    timedOut = true
                    finalizeReason = FocusFinalizeReason.SEARCH_DEADLINE
                }
                return improved
            }
            val next = (cursor + direction * coarseStep).coerceIn(0f, minimumFocusDistance)
            if (abs(next - cursor) < .0001f) return improved
            val bestBefore = state.bestProbe
            val sample = probe(next, phase) ?: return improved
            val comparison = state.observe(sample)
            logSweepSample(logger, focusSessionId, phase, sample)
            if (comparison > 0) {
                improved = true
                degradationRun = 0
            } else {
                degradationRun = nextFocusDegradationRun(
                    degradationRun,
                    comparison,
                    bestBefore?.candidateScore?.hasEvidence == true || bestBefore?.targetSharpness != null
                )
            }
            cursor = next

            if (comparison > 0 && shouldConfirmFocusEarly(sample, anchor)) {
                earlyConfirmed = true
                finalizeReason = FocusFinalizeReason.EARLY_CONFIRMED
                logger?.log(
                    "FOCUS_EARLY_CONFIRM",
                    "focusSession" to focusSessionId,
                    "phase" to phase,
                    "distance" to next,
                    "decoded" to sample.candidateScore.decodedHits,
                    "stable" to sample.candidateScore.stableCount,
                    "targetSharp" to sample.targetSharpness
                )
                return improved
            }
            val immediateTurn = comparison < 0 && bestBefore != null && isClearlyWorseFocusSample(sample, bestBefore)
            if (immediateTurn || shouldReverseFocusDirection(degradationRun, FOCUS_WORSE_PROBES_BEFORE_REVERSE)) {
                logger?.log(
                    "FOCUS_SWEEP_TURN",
                    "focusSession" to focusSessionId,
                    "phase" to phase,
                    "at" to next,
                    "best" to state.bestProbe?.distance,
                    "worseRun" to degradationRun,
                    "immediate" to if (immediateTurn) 1 else 0,
                    "targetSharp" to sample.targetSharpness,
                    "bestTargetSharp" to state.bestProbe?.targetSharpness
                )
                return improved
            }
        }
        return improved
    }

    if (!earlyConfirmed) {
        val firstDirection = when {
            anchorDistance <= coarseStep * .5f -> 1f
            anchorDistance >= minimumFocusDistance - coarseStep * .5f -> -1f
            else -> -1f
        }
        val firstImproved = exploreDirection(firstDirection, "local_a", mode.coarseSegments)
        if (!firstImproved && !earlyConfirmed && !timedOut) {
            exploreDirection(-firstDirection, "local_b", mode.coarseSegments)
        }
    }

    val localBest = state.bestProbe
    if (!earlyConfirmed && !timedOut && !state.hasUsefulBestProbe()) {
        logger?.log(
            "FOCUS_SWEEP_FALLBACK",
            "focusSession" to focusSessionId,
            "reason" to "no_useful_local_probe",
            "samples" to state.samples().size + 1,
            "targetSharp" to localBest?.targetSharpness
        )
        val sampledDistances = state.samples().map { it.distance } + listOfNotNull(startActual)
        for (distance in coarseFocusDistances(minimumFocusDistance, minOf(mode.coarseSegments, FOCUS_FALLBACK_SEGMENTS))) {
            if (SystemClock.elapsedRealtime() >= searchDeadline) {
                timedOut = true
                finalizeReason = FocusFinalizeReason.SEARCH_DEADLINE
                break
            }
            if (sampledDistances.any { abs(it - distance) <= coarseStep * .18f }) continue
            val sample = probe(distance, "fallback") ?: break
            state.observe(sample)
            logSweepSample(logger, focusSessionId, "fallback", sample)
            if (shouldConfirmFocusEarly(sample, anchor)) {
                earlyConfirmed = true
                finalizeReason = FocusFinalizeReason.EARLY_CONFIRMED
                break
            }
        }
    }

    if (shouldRunFineFocusPass(mode, state.bestProbe, earlyConfirmed, searchDeadline - SystemClock.elapsedRealtime())) {
        val fineAnchor = state.bestProbe
        if (fineAnchor != null) {
            for (distance in fineFocusDistances(minimumFocusDistance, fineAnchor.distance, coarseStep)) {
                if (SystemClock.elapsedRealtime() >= searchDeadline) {
                    timedOut = true
                    finalizeReason = FocusFinalizeReason.SEARCH_DEADLINE
                    break
                }
                val bestBefore = state.bestProbe
                val sample = probe(distance, "fine") ?: break
                val comparison = state.observe(sample)
                logSweepSample(logger, focusSessionId, "fine", sample)
                if (comparison > 0 && shouldConfirmFocusEarly(sample, fineAnchor)) {
                    earlyConfirmed = true
                    finalizeReason = FocusFinalizeReason.EARLY_CONFIRMED
                    break
                }
                if (comparison < 0 && bestBefore != null && isClearlyWorseFocusSample(sample, bestBefore)) break
            }
        }
    }

    if (SystemClock.elapsedRealtime() >= searchDeadline && !earlyConfirmed) {
        timedOut = true
        finalizeReason = FocusFinalizeReason.SEARCH_DEADLINE
    }
    if (cancelled || !isCurrent()) {
        logger?.log(
            "FOCUS_SWEEP_CANCELLED",
            "focusSession" to focusSessionId,
            "actualDistance" to metadata.latest()?.actualDistance,
            "lensCommands" to state.lensCommands
        )
        return ManualSweepResult(
            applied = false,
            success = false,
            skippedAlreadySharp = false,
            distance = metadata.latest()?.actualDistance,
            score = state.bestProbe?.candidateScore,
            finalScore = null,
            lensCommands = state.lensCommands,
            timedOut = false,
            cancelled = true
        )
    }
    val decision = state.finalizeDecision(finalizeReason)
    if (timedOut) {
        logger?.log(
            "FOCUS_TIMEOUT_FINALIZE",
            "focusSession" to focusSessionId,
            "startActual" to startActual,
            "lastRequested" to startRequested,
            "bestProbe" to state.bestProbe?.distance,
            "bestProbeSharp" to state.bestProbe?.targetSharpness,
            "savedFallback" to savedFallback,
            "action" to when {
                decision.retainedBestProbe -> "retain_best"
                decision.usedStartActual -> "restore_start_actual"
                decision.usedSavedFallback -> "restore_saved_fallback"
                else -> "hold_current"
            },
            "selected" to decision.distance,
            "lensCommands" to state.lensCommands
        )
    }

    val selectedDistance = decision.distance?.coerceIn(0f, minimumFocusDistance)
    var finalCommand: LensCommandOutcome? = null
    val latestBeforeFinalize = metadata.latest()
    val alreadyHeld = selectedDistance != null && latestBeforeFinalize?.actualDistance?.let {
        abs(it - selectedDistance) <= FocusLensMetadataMonitor.distanceTolerance(selectedDistance)
    } == true && (latestBeforeFinalize.stationary || latestBeforeFinalize.lensState == null)
    if (selectedDistance != null && !alreadyHeld && isCurrent()) {
        val requestId = ++nextRequestId
        state.commandSent()
        onLensRequested(selectedDistance)
        finalCommand = executeLensCommand(
            context,
            camera2Control,
            metadata,
            focusSessionId,
            requestId,
            "finalize",
            selectedDistance
        )
        if (!finalCommand.acknowledged) state.commandFailed()
    }

    analyzer.setFocusCandidateSamplingStrict(true)
    val confirmNotBeforeSensor = finalCommand?.appliedMetadata?.sensorTimestampNs
        ?: metadata.latest()?.sensorTimestampNs
        ?: 0L
    val finalMeasurement = awaitFocusMeasurement(
        analyzer = analyzer,
        metadata = metadata,
        notBeforeSensorTimestampNs = confirmNotBeforeSensor,
        timeoutMs = FOCUS_CONFIRM_TIMEOUT_MS,
        requiredCandidateFrames = FOCUS_CONFIRM_FRAMES,
        candidateGraceMs = 0L
    )
    analyzer.setFocusCandidateSamplingStrict(false)
    if (!isCurrent()) {
        return ManualSweepResult(
            applied = false,
            success = false,
            skippedAlreadySharp = false,
            distance = metadata.latest()?.actualDistance,
            score = state.bestProbe?.candidateScore,
            finalScore = null,
            lensCommands = state.lensCommands,
            timedOut = false,
            cancelled = true
        )
    }
    val finalScore = finalMeasurement.candidateScore
    val finalSharp = finalMeasurement.sharpness?.score
    val bestSharp = state.bestProbe?.targetSharpness
    val sharpnessConfirmed = finalSharp != null && (
        finalSharp >= MANUAL_FOCUS_SHARP_THRESHOLD ||
            (bestSharp != null && finalSharp >= max(FOCUS_ACCEPTABLE_TARGET_SHARPNESS, bestSharp * .78f))
        )
    val success = finalScore.hasStableCandidate || finalScore.decodedHits > 0 || sharpnessConfirmed
    val finalActual = finalMeasurement.metadata?.actualDistance ?: metadata.latest()?.actualDistance
    val physicallySelected = selectedDistance?.takeIf { selected ->
        finalActual?.let { abs(it - selected) <= FocusLensMetadataMonitor.distanceTolerance(selected) } == true
    }
    // Report the physical/final requested position even when visual confirmation fails. The
    // caller persists it only on success, but diagnostics must not lose where the lens was left.
    val reportedDistance = physicallySelected ?: selectedDistance.takeIf {
        finalMeasurement.metadata?.actualDistance == null && finalCommand?.acknowledged != false
    }

    if (timedOut || decision.usedStartActual || decision.usedSavedFallback) {
        logger?.log(
            "FOCUS_RECOVERY",
            "focusSession" to focusSessionId,
            "selected" to selectedDistance,
            "actual" to finalActual,
            "retainedBest" to if (decision.retainedBestProbe) 1 else 0,
            "usedStartActual" to if (decision.usedStartActual) 1 else 0,
            "usedSavedFallback" to if (decision.usedSavedFallback) 1 else 0,
            "confirmSuccess" to if (success) 1 else 0
        )
    }
    logSweepEnd(
        logger = logger,
        focusSessionId = focusSessionId,
        success = success,
        selectedDistance = selectedDistance,
        finalMeasurement = finalMeasurement,
        best = state.bestProbe,
        samples = state.samples().size + 1,
        path = if (earlyConfirmed) "early_confirm" else if (timedOut) "deadline_finalize" else "adaptive_finalize",
        lensCommands = state.lensCommands,
        timedOut = timedOut
    )
    return ManualSweepResult(
        applied = selectedDistance == null || alreadyHeld || finalCommand?.acknowledged == true,
        success = success,
        skippedAlreadySharp = false,
        distance = reportedDistance,
        score = state.bestProbe?.candidateScore,
        finalScore = finalScore,
        lensCommands = state.lensCommands,
        timedOut = timedOut
    )
}

private suspend fun runNativeAutoFocusSession(
    context: Context,
    camera: Camera,
    analyzer: DataMatrixAnalyzer,
    metadata: FocusLensMetadataMonitor,
    statistics: ScannerStatisticsStore,
    logger: PipelineDebugLogger?,
    focusSessionId: Long,
    reason: String,
    homeDistance: Float?,
    savedSuccessfulDistance: Float?,
    learnedAnchorDistance: Float?,
    lastRequestedDistance: Float?,
    isCurrent: () -> Boolean
): FocusSessionResult {
    val before = analyzer.currentCenterSharpness()
    val noCandidateBefore = statistics.currentNoCandidateFrames()
    val preAfMetadata = metadata.latest()
    val preAfSignal = analyzer.nativeAfSignal()
    val capabilities = readCameraFocusCapabilities(camera)
    val recovery = selectNativeAfRecovery(
        preAfActual = preAfMetadata?.actualDistance,
        preAfSharpness = before,
        preAfCandidateEvidence = preAfSignal.centralCandidateSignature != null &&
            SystemClock.elapsedRealtime() - preAfSignal.candidateElapsedMs <= NATIVE_AF_SIGNAL_FRESH_MS,
        homeDistance = homeDistance,
        savedSuccessfulDistance = savedSuccessfulDistance,
        learnedAnchorDistance = learnedAnchorDistance,
        lastRequestedDistance = lastRequestedDistance,
        minimumFocusDistance = capabilities.minimumFocusDistance
    )
    val regionSize = statistics.recommendedAfRegionSize().coerceIn(NATIVE_AF_MIN_REGION, NATIVE_AF_MAX_REGION)
    val startedAt = SystemClock.elapsedRealtime()
    val notBeforeSensor = preAfMetadata?.sensorTimestampNs ?: 0L
    logger?.log(
        "FOCUS_AUTO_START",
        "focusSession" to focusSessionId,
        "reason" to reason,
        "regionSize" to regionSize,
        "meteringPoint" to "0.5,0.5",
        "noCandidateFrames" to noCandidateBefore,
        "startActual" to preAfMetadata?.actualDistance,
        "rollbackTarget" to recovery.distance,
        "rollbackSource" to recovery.source
    )
    analyzer.onCenterFocusStarted()
    analyzer.setFocusSamplingActive(true)
    analyzer.setFocusCandidateSamplingStrict(true)
    val camera2Control = runCatching { Camera2CameraControl.from(camera.cameraControl) }.getOrNull()
    if (camera2Control != null) {
        awaitFutureOutcome(camera2Control.clearCaptureRequestOptions(), context, CAMERA_CONTROL_TIMEOUT_MS)
    }
    suspend fun attempt(size: Float, pass: String): Pair<FutureAwaitOutcome<androidx.camera.core.FocusMeteringResult>?, Boolean> {
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(.5f, .5f, size)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .addPoint(point, FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()
        logger?.log(
            "FOCUS_AF_REGION", "focusSession" to focusSessionId, "pass" to pass,
            "normalizedPoint" to "0.5,0.5", "normalizedSize" to size,
            "sensorRect" to readCameraFocusCapabilities(camera).sensorActiveArray,
            "cropRegion" to metadata.latest()?.cropRegion,
            "appliedAfRegions" to metadata.latest()?.afRegions
        )
        val future = runCatching { camera.cameraControl.startFocusAndMetering(action) }.getOrNull()
        val outcome = future?.let { awaitFutureOutcome(it, context, AUTO_FOCUS_TIMEOUT_MS) }
        return outcome to (outcome?.takeIf { it.completedSuccessfully }?.value?.isFocusSuccessful == true)
    }
    var (autofocusOutcome, focusLocked) = attempt(regionSize, "primary")
    var appliedRegionSize = regionSize
    if (!focusLocked && isCurrent()) {
        appliedRegionSize = (regionSize * 1.5f).coerceAtMost(NATIVE_AF_EXPANDED_MAX_REGION)
        logger?.log("FOCUS_AF_EXPAND", "focusSession" to focusSessionId, "from" to regionSize, "to" to appliedRegionSize)
        val expanded = attempt(appliedRegionSize, "single_expansion")
        autofocusOutcome = expanded.first
        focusLocked = expanded.second
    }
    if (!isCurrent()) {
        analyzer.setFocusCandidateSamplingStrict(false)
        analyzer.setFocusSamplingActive(false)
        return FocusSessionResult(
            success = false,
            skippedAlreadySharp = false,
            sharpnessBefore = before,
            sharpnessAfter = analyzer.currentCenterSharpness(),
            cancelled = true
        )
    }
    val stationaryMetadata = awaitPhysicalLensStationary(metadata, notBeforeSensor, NATIVE_AF_PHYSICAL_SETTLE_MS)
    val measurement = awaitFocusMeasurement(
        analyzer = analyzer,
        metadata = metadata,
        notBeforeSensorTimestampNs = stationaryMetadata?.sensorTimestampNs ?: notBeforeSensor,
        timeoutMs = AUTO_CONFIRM_TIMEOUT_MS,
        requiredCandidateFrames = FOCUS_CONFIRM_FRAMES,
        candidateGraceMs = 0L
    )
    analyzer.setFocusCandidateSamplingStrict(false)
    analyzer.setFocusSamplingActive(false)
    val sharp = measurement.sharpness?.score
    val evidence = measurement.candidateScore.hasStableCandidate || measurement.candidateScore.decodedHits > 0
    val success = focusLocked && (evidence || (sharp != null && sharp >= FOCUS_ACCEPTABLE_TARGET_SHARPNESS))
    analyzer.onCenterFocusCompleted(success)
    val rejectedActual = measurement.metadata?.actualDistance ?: metadata.latest()?.actualDistance
    var recoveryApplied = false
    var finalDistance = rejectedActual.takeIf { success }
    if (!success && isCurrent() && recovery.distance != null) {
        logger?.log(
            "FOCUS_NATIVE_ROLLBACK",
            "focusSession" to focusSessionId,
            "reason" to "validation_failed",
            "cameraXSuccess" to if (focusLocked) 1 else 0,
            "fromActual" to rejectedActual,
            "toDistance" to recovery.distance,
            "source" to recovery.source
        )
        recoveryApplied = applyHeldLensDistance(
            context = context,
            camera = camera,
            metadata = metadata,
            distance = recovery.distance,
            logger = logger,
            reason = "native_af_rollback"
        )
        if (recoveryApplied) {
            finalDistance = metadata.latest()?.actualDistance
                ?.takeIf { lensDistanceMatchesActual(recovery.distance, it) }
                ?: recovery.distance
        }
        logger?.log(
            "FOCUS_NATIVE_ROLLBACK_END",
            "focusSession" to focusSessionId,
            "applied" to if (recoveryApplied) 1 else 0,
            "requestedDistance" to recovery.distance,
            "actualDistance" to metadata.latest()?.actualDistance,
            "source" to recovery.source
        )
    }
    logger?.log(
        "FOCUS_AUTO_END",
        "focusSession" to focusSessionId,
        "cameraXSuccess" to if (focusLocked) 1 else 0,
        "success" to if (success) 1 else 0,
        "durationMs" to (SystemClock.elapsedRealtime() - startedAt),
        "regionSize" to appliedRegionSize,
        "actualDistance" to rejectedActual,
        "finalDistance" to finalDistance,
        "rollbackApplied" to if (recoveryApplied) 1 else 0,
        "physicalSettled" to if (stationaryMetadata != null) 1 else 0,
        "afMode" to FocusLensMetadataMonitor.afModeName(measurement.metadata?.afMode),
        "afState" to FocusLensMetadataMonitor.afStateName(measurement.metadata?.afState),
        "afRegions" to measurement.metadata?.afRegions,
        "cropRegion" to measurement.metadata?.cropRegion,
        "lensState" to FocusLensMetadataMonitor.lensStateName(measurement.metadata?.lensState),
        "stable" to measurement.candidateScore.stableCount,
        "decoded" to measurement.candidateScore.decodedHits,
        "targetSharp" to sharp,
        "reacquireFrames" to measurement.candidateScore.frameCount,
        "noCandidateFrames" to noCandidateBefore
    )
    return FocusSessionResult(
        success = success,
        skippedAlreadySharp = false,
        sharpnessBefore = before,
        sharpnessAfter = sharp ?: analyzer.currentCenterSharpness(),
        selectedDistance = finalDistance,
        stableCandidates = measurement.candidateScore.stableCount,
        lensSteps = 0,
        timedOut = autofocusOutcome?.completedSuccessfully != true,
        apparentArea = measurement.candidateScore.apparentArea,
        noCandidateFramesBefore = noCandidateBefore,
        reacquireFrames = measurement.candidateScore.frameCount,
        reacquireMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    )
}

internal data class FocusRecoveryChoice(val distance: Float?, val source: String)

internal fun selectNativeAfRecovery(
    preAfActual: Float?,
    preAfSharpness: Float?,
    preAfCandidateEvidence: Boolean,
    homeDistance: Float?,
    savedSuccessfulDistance: Float?,
    learnedAnchorDistance: Float?,
    lastRequestedDistance: Float?,
    minimumFocusDistance: Float?
): FocusRecoveryChoice {
    fun valid(value: Float?): Float? {
        val finite = value?.takeIf(Float::isFinite) ?: return null
        return minimumFocusDistance?.takeIf { it.isFinite() && it > 0f }?.let { finite.coerceIn(0f, it) }
            ?: finite.coerceAtLeast(0f)
    }
    // Preserve the physical position that was producing useful scene evidence immediately before
    // CameraX was allowed to sweep. Otherwise prefer explicit/user-confirmed or learned anchors.
    if (preAfCandidateEvidence || (preAfSharpness != null && preAfSharpness >= FOCUS_ACCEPTABLE_TARGET_SHARPNESS)) {
        valid(preAfActual)?.let { return FocusRecoveryChoice(it, "pre_af_actual") }
    }
    valid(homeDistance)?.let { return FocusRecoveryChoice(it, "HOME") }
    valid(savedSuccessfulDistance)?.let { return FocusRecoveryChoice(it, "last_success") }
    valid(learnedAnchorDistance)?.let { return FocusRecoveryChoice(it, "learned_anchor") }
    valid(lastRequestedDistance)?.let { return FocusRecoveryChoice(it, "last_requested") }
    valid(preAfActual)?.let { return FocusRecoveryChoice(it, "pre_af_actual_unvalidated") }
    return FocusRecoveryChoice(null, "none")
}

internal fun selectManualSearchAnchor(
    homeDistance: Float?,
    savedSuccessfulDistance: Float?,
    learnedAnchorDistance: Float?,
    lastRequestedDistance: Float?,
    startActual: Float?,
    minimumFocusDistance: Float
): Float = listOf(
    homeDistance, savedSuccessfulDistance, learnedAnchorDistance, lastRequestedDistance, startActual
).firstOrNull { it != null && it.isFinite() }?.coerceIn(0f, minimumFocusDistance)
    ?: minimumFocusDistance * .5f

private suspend fun executeLensCommand(
    context: Context,
    camera2Control: Camera2CameraControl,
    metadata: FocusLensMetadataMonitor,
    focusSessionId: Long,
    requestId: Long,
    phase: String,
    distance: Float
): LensCommandOutcome {
    val request = metadata.requestSent(focusSessionId, requestId, phase, distance)
    val options = CaptureRequestOptions.Builder()
        .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
        .build()
    val future = runCatching { camera2Control.setCaptureRequestOptions(options) }.getOrNull()
    val acknowledged = future?.let {
        awaitFutureOutcome(it, context, CAMERA_CONTROL_TIMEOUT_MS).completedSuccessfully
    } == true
    val error = if (future == null) "request_exception" else if (!acknowledged) "camera_control_timeout" else null
    val completedRequest = metadata.requestAcknowledged(request, acknowledged, error)
    if (!acknowledged) return LensCommandOutcome(completedRequest, false, null, false, error)

    val applied = awaitLensApplied(metadata, completedRequest, LENS_APPLY_TIMEOUT_MS)
    if (applied != null) return LensCommandOutcome(completedRequest, true, applied, false, null)

    // Some devices omit LENS_STATE or focus distance from CaptureResult. Keep a clearly logged,
    // bounded fallback path instead of pretending a fixed sleep proved physical settle.
    delay(LENS_METADATA_FALLBACK_SETTLE_MS)
    return LensCommandOutcome(completedRequest, true, null, true, "metadata_unavailable_or_timeout")
}

private suspend fun applyHeldLensDistance(
    context: Context,
    camera: Camera,
    metadata: FocusLensMetadataMonitor,
    distance: Float,
    logger: PipelineDebugLogger?,
    reason: String
): Boolean {
    awaitFutureOutcome(camera.cameraControl.cancelFocusAndMetering(), context, CANCEL_AF_TIMEOUT_MS)
    val camera2Control = runCatching { Camera2CameraControl.from(camera.cameraControl) }.getOrNull() ?: return false
    val sessionId = SystemClock.elapsedRealtimeNanos()
    val outcome = executeLensCommand(
        context = context,
        camera2Control = camera2Control,
        metadata = metadata,
        focusSessionId = sessionId,
        requestId = 1L,
        phase = reason,
        distance = distance
    )
    logger?.log(
        "FOCUS_HOLD_APPLY",
        "reason" to reason,
        "requestedDistance" to distance,
        "actualDistance" to (outcome.appliedMetadata?.actualDistance ?: metadata.latest()?.actualDistance),
        "acknowledged" to if (outcome.acknowledged) 1 else 0,
        "metadataFallback" to if (outcome.usedMetadataFallback) 1 else 0,
        "error" to outcome.error
    )
    return outcome.acknowledged
}

private suspend fun awaitLensApplied(
    metadata: FocusLensMetadataMonitor,
    request: FocusLensRequest,
    timeoutMs: Long
): FocusLensMetadata? {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    var reached: FocusLensMetadata? = null
    while (SystemClock.elapsedRealtime() < deadline) {
        val current = metadata.latest()
        if (current != null && current.observedElapsedMs >= request.sentElapsedMs) {
            val actual = current.actualDistance
            val matches = actual != null && abs(actual - request.requestedDistance) <=
                FocusLensMetadataMonitor.distanceTolerance(request.requestedDistance)
            if (matches) {
                reached = current
                if (current.stationary || current.lensState == null) return current
            }
        }
        delay(4L)
    }
    return reached?.takeIf { it.stationary || it.lensState == null }
}

private suspend fun awaitFocusMeasurement(
    analyzer: DataMatrixAnalyzer,
    metadata: FocusLensMetadataMonitor,
    notBeforeSensorTimestampNs: Long,
    timeoutMs: Long,
    requiredCandidateFrames: Int,
    candidateGraceMs: Long
): FocusMeasurement {
    val requiredConsecutive = requiredCandidateFrames.coerceAtLeast(1)
    val tracker = FocusCandidateWindowTracker(requiredConsecutiveFrames = requiredConsecutive)
    var lastCandidateFrameId = -1L
    var sharpness: CenterSharpnessSnapshot? = null
    var sharpnessSeenAt = 0L
    val deadline = SystemClock.elapsedRealtime() + timeoutMs

    while (SystemClock.elapsedRealtime() < deadline) {
        analyzer.currentCenterSharpnessSnapshot()?.let { snapshot ->
            if (
                snapshot.sensorTimestampNs > notBeforeSensorTimestampNs &&
                (sharpness?.let { snapshot.sensorTimestampNs > it.sensorTimestampNs } ?: true)
            ) {
                sharpness = snapshot
                if (sharpnessSeenAt == 0L) sharpnessSeenAt = SystemClock.elapsedRealtime()
            }
        }
        val candidate = analyzer.currentFocusCandidateFrameSnapshot()
        if (
            candidate != null && candidate.frameId != lastCandidateFrameId &&
            candidate.sensorTimestampNs > notBeforeSensorTimestampNs
        ) {
            tracker.addFrame(candidate.observations)
            lastCandidateFrameId = candidate.frameId
        }
        val strictSatisfied = requiredCandidateFrames > 0 && tracker.framesSeen >= requiredCandidateFrames && sharpness != null
        val probeSatisfied = requiredCandidateFrames == 0 && sharpness != null && (
            tracker.framesSeen > 0 || SystemClock.elapsedRealtime() - sharpnessSeenAt >= candidateGraceMs
            )
        if (strictSatisfied || probeSatisfied) break
        delay(4L)
    }
    val matchedMetadata = sharpness?.let { metadata.closest(it.sensorTimestampNs) }
    return FocusMeasurement(tracker.score(), sharpness, matchedMetadata)
}

private fun FocusMeasurement.toSample(
    distance: Float,
    requestedDistance: Float?,
    requestId: Long?,
    commandAckMs: Long?,
    settleMs: Long?,
    metadataFallback: Boolean = false
): FocusSweepSample = FocusSweepSample(
    distance = distance,
    candidateScore = candidateScore,
    targetSharpness = sharpness?.score,
    targetCoreSharpness = sharpness?.core,
    targetContextSharpness = sharpness?.context,
    requestId = requestId,
    requestedDistance = requestedDistance,
    actualDistance = metadata?.actualDistance,
    lensState = metadata?.lensState,
    analyzerFrameId = sharpness?.analyzerFrameId,
    cameraFrameNumber = metadata?.frameNumber,
    sensorTimestampNs = sharpness?.sensorTimestampNs,
    commandAckMs = commandAckMs,
    settleMs = settleMs,
    exposureTimeNs = metadata?.exposureTimeNs,
    iso = metadata?.iso,
    metadataFallback = metadataFallback
)

private fun logSweepSample(
    logger: PipelineDebugLogger?,
    focusSessionId: Long,
    phase: String,
    sample: FocusSweepSample
) {
    val score = sample.candidateScore
    logger?.log(
        "FOCUS_SWEEP_SAMPLE",
        "focusSession" to focusSessionId,
        "phase" to phase,
        "requestId" to sample.requestId,
        "requestedDistance" to sample.requestedDistance,
        "actualDistance" to sample.actualDistance,
        "lensState" to FocusLensMetadataMonitor.lensStateName(sample.lensState),
        "distance" to sample.distance.takeIf(Float::isFinite),
        "commandAckMs" to sample.commandAckMs,
        "settleMs" to sample.settleMs,
        "metadataFallback" to if (sample.metadataFallback) 1 else 0,
        "analyzerFrame" to sample.analyzerFrameId,
        "cameraFrame" to sample.cameraFrameNumber,
        "sensorTimestamp" to sample.sensorTimestampNs,
        "exposureNs" to sample.exposureTimeNs,
        "iso" to sample.iso,
        "stable" to score.stableCount,
        "decodedStable" to score.stableDecodedCount,
        "decodedHits" to score.decodedHits,
        "runStrength" to score.consecutiveStrength,
        "hits" to score.totalHits,
        "frames" to score.frameCount,
        "consistency" to score.consistency,
        "apparentArea" to score.apparentArea,
        "centerDistance" to score.centerDistance,
        "points" to score.points,
        "targetSharp" to sample.targetSharpness,
        "coreSharp" to sample.targetCoreSharpness,
        "contextSharp" to sample.targetContextSharpness
    )
}

private fun logSweepEnd(
    logger: PipelineDebugLogger?,
    focusSessionId: Long,
    success: Boolean,
    selectedDistance: Float?,
    finalMeasurement: FocusMeasurement,
    best: FocusSweepSample?,
    samples: Int,
    path: String,
    lensCommands: Int,
    timedOut: Boolean
) {
    val finalScore = finalMeasurement.candidateScore
    logger?.log(
        "FOCUS_SWEEP_END",
        "focusSession" to focusSessionId,
        "success" to if (success) 1 else 0,
        "selectedDistance" to selectedDistance,
        "actualDistance" to finalMeasurement.metadata?.actualDistance,
        "lensState" to FocusLensMetadataMonitor.lensStateName(finalMeasurement.metadata?.lensState),
        "stable" to finalScore.stableCount,
        "decodedStable" to finalScore.stableDecodedCount,
        "decodedHits" to finalScore.decodedHits,
        "targetSharp" to finalMeasurement.sharpness?.score,
        "coreSharp" to finalMeasurement.sharpness?.core,
        "contextSharp" to finalMeasurement.sharpness?.context,
        "bestProbeDistance" to best?.distance,
        "bestProbeSharp" to best?.targetSharpness,
        "frames" to finalScore.frameCount,
        "samples" to samples,
        "lensCommands" to lensCommands,
        "timedOut" to if (timedOut) 1 else 0,
        "path" to path
    )
}

private data class CameraFocusCapabilities(
    val cameraId: String,
    val hardwareLevel: String,
    val minimumFocusDistance: Float?,
    val focusCalibration: String,
    val afModes: String,
    val maxRegionsAf: Int?,
    val sensorActiveArray: String?
)

private fun readCameraFocusCapabilities(camera: Camera): CameraFocusCapabilities {
    val info = Camera2CameraInfo.from(camera.cameraInfo)
    val hardware = info.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
    val calibration = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
    val afModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
    return CameraFocusCapabilities(
        cameraId = runCatching { info.cameraId }.getOrDefault("unknown"),
        hardwareLevel = hardwareLevelName(hardware),
        minimumFocusDistance = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
        focusCalibration = focusCalibrationName(calibration),
        afModes = afModes?.joinToString(",") { afModeName(it) } ?: "unknown",
        maxRegionsAf = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_MAX_REGIONS_AF),
        sensorActiveArray = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toShortString()
    )
}

private fun logCameraCapabilities(logger: PipelineDebugLogger?, sessionId: Long, value: CameraFocusCapabilities) {
    logger?.log(
        "FOCUS_CAMERA_INFO",
        "focusSession" to sessionId,
        "cameraId" to value.cameraId,
        "hardwareLevel" to value.hardwareLevel,
        "minimumFocusDistance" to value.minimumFocusDistance,
        "focusCalibration" to value.focusCalibration,
        "afModes" to value.afModes,
        "maxRegionsAf" to value.maxRegionsAf,
        "sensorActiveArray" to value.sensorActiveArray
    )
}

private suspend fun awaitPhysicalLensStationary(
    metadata: FocusLensMetadataMonitor,
    notBeforeSensorTimestampNs: Long,
    timeoutMs: Long
): FocusLensMetadata? {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    var firstStationaryFrame = -1L
    while (SystemClock.elapsedRealtime() < deadline) {
        val latest = metadata.latest()
        if (latest != null && latest.sensorTimestampNs >= notBeforeSensorTimestampNs &&
            (latest.stationary || latest.lensState == null)
        ) {
            if (firstStationaryFrame < 0L) firstStationaryFrame = latest.frameNumber
            if (latest.frameNumber > firstStationaryFrame || latest.lensState == null) return latest
        } else {
            firstStationaryFrame = -1L
        }
        delay(15L)
    }
    return null
}

private suspend fun disableDeviceAutoFocus(context: Context, camera: Camera): Boolean {
    val camera2Control = runCatching { Camera2CameraControl.from(camera.cameraControl) }.getOrNull() ?: return false
    val options = CaptureRequestOptions.Builder()
        .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        .build()
    return awaitFutureOutcome(
        camera2Control.setCaptureRequestOptions(options),
        context,
        CAMERA_CONTROL_TIMEOUT_MS
    ).completedSuccessfully
}

private data class FutureAwaitOutcome<T>(
    val completedSuccessfully: Boolean,
    val value: T?
)

private suspend fun <T> awaitFutureOutcome(
    future: ListenableFuture<T>,
    context: Context,
    timeoutMs: Long
): FutureAwaitOutcome<T> =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            future.addListener(
                {
                    val outcome = runCatching { future.get() }.fold(
                        onSuccess = { FutureAwaitOutcome(true, it) },
                        onFailure = { FutureAwaitOutcome<T>(false, null) }
                    )
                    if (continuation.isActive) continuation.resume(outcome)
                },
                ContextCompat.getMainExecutor(context)
            )
            continuation.invokeOnCancellation { future.cancel(true) }
        }
    } ?: FutureAwaitOutcome(false, null)

private class FocusSessionHaptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun strongPulse() {
        val target = vibrator ?: return
        if (!target.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        } else {
            VibrationEffect.createOneShot(62L, 255)
        }
        target.vibrate(effect)
    }
}

private fun hardwareLevelName(value: Int?): String = when (value) {
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
    null -> "UNKNOWN"
    else -> value.toString()
}

private fun focusCalibrationName(value: Int?): String = when (value) {
    CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> "UNCALIBRATED"
    CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
    CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
    null -> "UNKNOWN"
    else -> value.toString()
}

private fun afModeName(value: Int): String = when (value) {
    CaptureRequest.CONTROL_AF_MODE_OFF -> "OFF"
    CaptureRequest.CONTROL_AF_MODE_AUTO -> "AUTO"
    CaptureRequest.CONTROL_AF_MODE_MACRO -> "MACRO"
    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONT_VIDEO"
    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONT_PICTURE"
    CaptureRequest.CONTROL_AF_MODE_EDOF -> "EDOF"
    else -> value.toString()
}

private const val FOCUS_PROBE_FRAMES = 0
private const val FOCUS_PRECHECK_FRAMES = 2
private const val FOCUS_CONFIRM_FRAMES = 2
private const val FOCUS_WORSE_PROBES_BEFORE_REVERSE = 2
private const val FOCUS_FALLBACK_SEGMENTS = 4
private const val FOCUS_PROBE_TIMEOUT_MS = 230L
private const val FOCUS_PROBE_CANDIDATE_GRACE_MS = 36L
private const val FOCUS_PRECHECK_TIMEOUT_MS = 420L
private const val FOCUS_CONFIRM_TIMEOUT_MS = 560L
private const val FOCUS_ACCEPTABLE_TARGET_SHARPNESS = 9f
private const val CAMERA_CONTROL_TIMEOUT_MS = 480L
private const val LENS_APPLY_TIMEOUT_MS = 320L
private const val LENS_METADATA_FALLBACK_SETTLE_MS = 36L
private const val FAST_SEARCH_BUDGET_MS = 1_850L
private const val PRECISE_SEARCH_BUDGET_MS = 2_850L
private const val CANCEL_AF_TIMEOUT_MS = 320L
private const val AUTO_FOCUS_TIMEOUT_MS = 2_400L
private const val AUTO_CONFIRM_TIMEOUT_MS = 650L
private const val AUTO_NOMINAL_PROGRESS_MS = 1_500f
private const val NATIVE_AF_SIGNAL_FRESH_MS = 1_200L
private const val NATIVE_AF_SUPERVISION_COOLDOWN_MS = 1_100L
private const val NATIVE_AF_WATCH_INTERVAL_MS = 90L
private const val IDLE_PARK_NO_CANDIDATE_MS = 1_800L
private const val IDLE_PARK_STABLE_SCENE_MS = 900L
private const val IDLE_PARK_WATCH_INTERVAL_MS = 160L
private const val FOCUS_CONTROL_REFRESH_MS = 50L
private const val FOCUS_METADATA_STALE_MS = 500L
private const val NATIVE_AF_PHYSICAL_SETTLE_MS = 720L
private const val NATIVE_AF_MIN_REGION = .08f
private const val NATIVE_AF_MAX_REGION = .12f
private const val NATIVE_AF_EXPANDED_MAX_REGION = .18f
