package com.kopandazavr.datamatrixscanner

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.view.LifecycleCameraController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    val onTap: () -> Unit,
    val onLongPress: () -> Unit
)

private enum class FocusRequest { MANUAL, AUTO }

@Composable
internal fun rememberCameraFocusController(
    active: Boolean,
    controller: LifecycleCameraController?,
    analyzer: DataMatrixAnalyzer
): CameraFocusController {
    val context = LocalContext.current
    val requests = remember { Channel<FocusRequest>(capacity = Channel.CONFLATED) }
    val autoGate = remember { AutoFocusGate() }
    var autoEnabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(active, controller) {
        if (!active || controller == null) return@LaunchedEffect
        for (request in requests) {
            busy = true
            try {
                runCenterFocusSession(context, controller, analyzer)
                if (request == FocusRequest.AUTO) {
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

        // Switching auto mode on establishes a fresh, known focus point immediately.
        autoGate.markSessionStarted(SystemClock.elapsedRealtime())
        requests.trySend(FocusRequest.AUTO)

        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (!busy && autoGate.shouldRequest(analyzer.needsCenterRefocus(), now)) {
                requests.trySend(FocusRequest.AUTO)
            }
            delay(150L)
        }
    }

    return CameraFocusController(
        autoEnabled = autoEnabled,
        busy = busy,
        onTap = {
            // A normal tap always means one finite focus session. If auto mode was
            // active, the same tap also disables it exactly as the UI indicates.
            if (autoEnabled) autoEnabled = false
            requests.trySend(FocusRequest.MANUAL)
        },
        onLongPress = {
            if (!autoEnabled) autoEnabled = true
        }
    )
}

/**
 * One user-visible focus session. CameraX gets at most two AF attempts. Between
 * them the analyzer has time to resample its existing centre-weighted sharpness
 * metric (inner pixels around the cross count more than outer pixels). Even if
 * the signal remains pessimistic after the second attempt, the session stops.
 */
private suspend fun runCenterFocusSession(
    context: Context,
    controller: LifecycleCameraController,
    analyzer: DataMatrixAnalyzer
): Boolean {
    var anySuccess = false
    repeat(2) { attempt ->
        analyzer.onCenterFocusStarted()
        val success = performCenterFocusAttempt(context, controller)
        analyzer.onCenterFocusCompleted(success)
        anySuccess = anySuccess || success

        if (attempt == 0) {
            // The analyzer samples focus every 250 ms. Give it one new stable
            // sample before deciding whether a second AF attempt is worthwhile.
            delay(450L)
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
