package com.kopandazavr.datamatrixscanner

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val CameraControlBlue = Color(0xFF2563EB)

@Composable
internal fun EnhancementCycleButton(
    fullscreen: Boolean,
    active: Boolean,
    progress: Float,
    onClick: () -> Unit
) {
    val size = if (fullscreen) 62.dp else 66.dp
    val shape = CircleShape
    Surface(
        onClick = { if (!active) onClick() },
        modifier = Modifier.size(size),
        shape = shape,
        color = if (active) CameraControlBlue else Color.Black.copy(alpha = if (fullscreen) .42f else .46f),
        border = BorderStroke(
            if (active) 2.dp else 1.dp,
            if (active) Color(0xFF93C5FD) else Color.White.copy(alpha = if (fullscreen) .62f else .56f)
        )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (active) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(progress.coerceIn(0f, 1f))
                        .background(Color.White.copy(alpha = .24f))
                )
            }
            Icon(
                Icons.Default.Speed,
                contentDescription = if (active) "Усиленный цикл выполняется" else "Усиленный цикл",
                tint = Color.White,
                modifier = Modifier.size(if (fullscreen) 35.dp else 42.dp)
            )
        }
    }
}

@Composable
internal fun FocusModeButton(
    fullscreen: Boolean,
    busy: Boolean,
    nativeAfActive: Boolean,
    nominalProgressMs: Float,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(busy, nominalProgressMs) {
        if (busy) {
            progress = 0f
            val startedAt = SystemClock.elapsedRealtime()
            val nominal = nominalProgressMs.coerceAtLeast(250f)
            while (true) {
                progress = ((SystemClock.elapsedRealtime() - startedAt) / nominal).coerceIn(0f, .94f)
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

    val size = if (fullscreen) 62.dp else 66.dp
    val shape = CircleShape
    val background = if (nativeAfActive) CameraControlBlue
        else Color.Black.copy(alpha = if (fullscreen) .42f else .46f)
    val borderColor = if (nativeAfActive) Color(0xFF93C5FD)
        else if (busy) Color.White.copy(alpha = .30f + progress * .70f)
        else Color.White.copy(alpha = if (fullscreen) .62f else .56f)

    val platformConfiguration = LocalViewConfiguration.current
    val focusConfiguration = remember(platformConfiguration) {
        object : ViewConfiguration by platformConfiguration {
            override val longPressTimeoutMillis: Long = 500L
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides focusConfiguration) {
        Surface(
            modifier = Modifier
                .size(size)
                .pointerInput(busy, nativeAfActive, onTap, onLongPress) {
                    detectTapGestures(
                        onTap = { if (!busy || nativeAfActive) onTap() },
                        onLongPress = { if (!busy) onLongPress() }
                    )
                },
            shape = shape,
            color = background,
            border = BorderStroke(if (busy || nativeAfActive) 2.dp else 1.dp, borderColor)
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
                    imageVector = if (busy && !nativeAfActive) Icons.Default.Lock else Icons.Default.CenterFocusStrong,
                    contentDescription = when {
                        nativeAfActive -> "Нативный автофокус включён; нажать — выключить и запустить ручной фокус"
                        busy -> "Ручной фокус выполняется"
                        else -> "Фокус: нажать — ручной, удерживать — авто"
                    },
                    tint = Color.White,
                    modifier = Modifier.size(if (fullscreen) 35.dp else 42.dp)
                )
            }
        }
    }
}
