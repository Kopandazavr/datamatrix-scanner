package com.kopandazavr.datamatrixscanner

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CameraControlBlue = Color(0xFF2563EB)
private const val FocusProgressNominalMs = 1_800f

@Composable
internal fun EnhancementHoldButton(
    fullscreen: Boolean,
    onPulse: () -> Unit,
    onActiveChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val size = if (fullscreen) 62.dp else 44.dp
    val shape = if (fullscreen) CircleShape else RoundedCornerShape(10.dp)
    val modifier = Modifier
        .size(size)
        .pointerInput(onPulse, onActiveChange) {
            detectTapGestures(
                onPress = {
                    onActiveChange(true)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    coroutineScope {
                        val pulseJob = launch {
                            while (true) {
                                onPulse()
                                delay(650L)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            pulseJob.cancel()
                            onActiveChange(false)
                        }
                    }
                }
            )
        }

    Surface(
        modifier = modifier,
        shape = shape,
        color = Color.Black.copy(alpha = if (fullscreen) .42f else .46f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (fullscreen) .62f else .56f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Speed,
                contentDescription = "Усиление 120%",
                tint = Color.White,
                modifier = Modifier.size(if (fullscreen) 35.dp else 29.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FocusModeButton(
    fullscreen: Boolean,
    autoEnabled: Boolean,
    busy: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val platformConfiguration = LocalViewConfiguration.current
    val halfSecondLongPress = remember(platformConfiguration) {
        object : ViewConfiguration by platformConfiguration {
            override val longPressTimeoutMillis: Long = 500L
        }
    }
    var progress by remember { mutableFloatStateOf(0f) }

    // The clock is visual guidance, but unlock is tied to the real CameraX busy
    // state. Long focus sessions stop at 94% and only reach 100% when AF finishes.
    LaunchedEffect(busy) {
        if (busy) {
            progress = 0f
            val startedAt = SystemClock.elapsedRealtime()
            while (true) {
                progress = ((SystemClock.elapsedRealtime() - startedAt) / FocusProgressNominalMs)
                    .coerceIn(0f, .94f)
                delay(16L)
            }
        } else {
            if (progress > 0f) {
                progress = 1f
                delay(120L)
            }
            progress = 0f
        }
    }

    val size = if (fullscreen) 62.dp else 44.dp
    val shape = if (fullscreen) CircleShape else RoundedCornerShape(10.dp)
    val background = if (autoEnabled) CameraControlBlue else Color.Black.copy(alpha = if (fullscreen) .42f else .46f)
    val borderColor = when {
        busy -> Color.White.copy(alpha = .30f + progress * .70f)
        autoEnabled -> Color(0xFF93C5FD)
        else -> Color.White.copy(alpha = if (fullscreen) .62f else .56f)
    }
    val border = BorderStroke(if (busy) 2.dp else 1.dp, borderColor)

    CompositionLocalProvider(LocalViewConfiguration provides halfSecondLongPress) {
        Surface(
            modifier = Modifier
                .size(size)
                .combinedClickable(
                    enabled = !busy,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTap()
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                ),
            shape = shape,
            color = background,
            border = border
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (busy && progress > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(progress)
                            .background(Color.White.copy(alpha = .22f))
                    )
                }
                Icon(
                    imageVector = if (busy) Icons.Default.Lock else Icons.Default.CenterFocusStrong,
                    contentDescription = when {
                        busy -> "Фокусировка — кнопка заблокирована"
                        autoEnabled -> "Автофокус включён"
                        else -> "Ручной фокус"
                    },
                    tint = Color.White,
                    modifier = Modifier.size(if (fullscreen) 35.dp else 29.dp)
                )
            }
        }
    }
}
