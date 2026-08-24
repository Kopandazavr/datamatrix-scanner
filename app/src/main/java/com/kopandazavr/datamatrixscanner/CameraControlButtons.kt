package com.kopandazavr.datamatrixscanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
        Box(contentAlignment = Alignment.Center) {
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
    val size = if (fullscreen) 62.dp else 44.dp
    val shape = if (fullscreen) CircleShape else RoundedCornerShape(10.dp)
    val background = if (autoEnabled) CameraControlBlue else Color.Black.copy(alpha = if (fullscreen) .42f else .46f)
    val border = if (autoEnabled) {
        BorderStroke(1.dp, Color(0xFF93C5FD))
    } else {
        BorderStroke(1.dp, Color.White.copy(alpha = if (fullscreen) .62f else .56f))
    }

    CompositionLocalProvider(LocalViewConfiguration provides halfSecondLongPress) {
        Surface(
            modifier = Modifier
                .size(size)
                .combinedClickable(
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
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.CenterFocusStrong,
                    contentDescription = if (autoEnabled) "Автофокус включён" else "Ручной фокус",
                    tint = Color.White.copy(alpha = if (busy) .62f else 1f),
                    modifier = Modifier.size(if (fullscreen) 35.dp else 29.dp)
                )
            }
        }
    }
}
