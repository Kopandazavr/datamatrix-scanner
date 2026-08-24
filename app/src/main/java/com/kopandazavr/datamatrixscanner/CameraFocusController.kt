package com.kopandazavr.datamatrixscanner

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.view.LifecycleCameraController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.kopandazavr.datamatrixscanner.scanner.AutoFocusGate
import com.kopandazavr.datamatrixscanner.scanner.DataMatrixAnalyzer
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class CameraFocusController(
    val autoEnabled: Boolean,
    val busy: Boolean,
    val manualMode: ManualFocusMode,
    val nominalProgressMs: Float,
    val onTap: () -> Unit,
    val onLongPress: () -> Unit,
    val onManualModeChange: (ManualFocusMode) -> Unit
)

private data class FocusRequest(
    val automatic: Boolean,
    val mode: ManualFocusMode
)

private const val FocusPreferencesName = "camera_focus_preferences"
private const val ManualFocusModePreference = "manual_focus_mode"

@Composable
internal fun rememberCameraFocusController(
    active: Boolean,
    controller: LifecycleCameraController?,
    analyzer: DataMatrixAnalyzer
): CameraFocusController {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(FocusPreferencesName, Context.MODE_PRIVATE)
    }
    val requests = remember { Channel<FocusRequest>(capacity = Channel.CONFLATED) }
    val autoGate = remember { AutoFocusGate() }
    var autoEnabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var manualMode by remember {
        mutableStateOf(
            ManualFocusMode.fromPreference(preferences.getString(ManualFocusModePreference, null))
        )
    }
    var nominalProgressMs by remember { mutableFloatStateOf(manualMode.nominalProgressMs) }

    LaunchedEffect(active, controller) {
        if (!active || controller == null) return@LaunchedEffect
        for (request in requests) {
            nominalProgressMs = request.mode.nominalProgressMs
            busy = true
            try {
                runCenterFocusSession(context, controller, analyzer, request.mode)
                if (request.automatic) {
                    autoGate.markSessionFinished(SystemClock.elapsedRealtime())
                }
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(active) {
        if (!active) autoEnabled = false
    }

    LaunchedEffect(active, controller, autoEnabled) {
        autoGate.reset()
        if (!active || controller == null || !autoEnabled) return@LaunchedEffect

        // Auto mode deliberately keeps the existing quality-first two-pass session.
        // The user's Fast/Precise choice applies only to explicit manual taps.
        autoGate.markSessionStarted(SystemClock.elapsedRealtime())
        requests.trySend(FocusRequest(automatic = true, mode = ManualFocusMode.PRECISE))

        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (!busy && autoGate.shouldRequest(analyzer.needsCenterRefocus(), now)) {
                requests.trySend(FocusRequest(automatic = true, mode = ManualFocusMode.PRECISE))
            }
            delay(150L)
        }
    }

    return CameraFocusController(
        autoEnabled = autoEnabled,
        busy = busy,
        manualMode = manualMode,
        nominalProgressMs = nominalProgressMs,
        onTap = {
            // A normal tap always means one finite manual focus session. If auto mode
            // was active, the same tap also disables it exactly as the UI indicates.
            if (autoEnabled) autoEnabled = false
            requests.trySend(FocusRequest(automatic = false, mode = manualMode))
        },
        onLongPress = {
            if (!autoEnabled) autoEnabled = true
        },
        onManualModeChange = { mode ->
            manualMode = mode
            if (!busy) nominalProgressMs = mode.nominalProgressMs
            preferences.edit().putString(ManualFocusModePreference, mode.name).apply()
        }
    )
}

/**
 * One finite focus session. FAST asks CameraX for a single central AF result and
 * adds no artificial wait, so its duration is almost entirely the device's own AF
 * time. PRECISE preserves the quality-first two-pass behaviour: after the first AF
 * it waits for a fresh centre-sharpness sample and performs a second AF only when
 * the first result still looks insufficient. Auto mode always uses PRECISE.
 */
private suspend fun runCenterFocusSession(
    context: Context,
    controller: LifecycleCameraController,
    analyzer: DataMatrixAnalyzer,
    mode: ManualFocusMode
): Boolean {
    var anySuccess = false
    repeat(mode.maxAttempts) { attempt ->
        analyzer.onCenterFocusStarted()
        val success = performCenterFocusAttempt(context, controller)
        analyzer.onCenterFocusCompleted(success)
        anySuccess = anySuccess || success

        val hasAnotherAttempt = attempt + 1 < mode.maxAttempts
        if (hasAnotherAttempt) {
            delay(mode.settleDelayMs)
            if (success && !analyzer.needsCenterRefocus()) return true
        }
    }
    return anySuccess
}

private suspend fun performCenterFocusAttempt(
    context: Context,
    controller: LifecycleCameraController
): Boolean {
    val point = SurfaceOrientedMeteringPointFactory(1f, 1f)
        .createPoint(.5f, .5f, .10f)
    val action = FocusMeteringAction.Builder(
        point,
        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
    )
        // Keep the result instead of releasing focus back to a potentially blurry state.
        .disableAutoCancel()
        .build()
    val future = runCatching {
        controller.cameraControl?.startFocusAndMetering(action)
    }.getOrNull() ?: return false

    return suspendCancellableCoroutine { continuation ->
        future.addListener(
            {
                val success = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                if (continuation.isActive) continuation.resume(success)
            },
            ContextCompat.getMainExecutor(context)
        )
        continuation.invokeOnCancellation { future.cancel(true) }
    }
}
