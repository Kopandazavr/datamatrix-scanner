package com.kopandazavr.datamatrixscanner

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

internal enum class UiHapticAction {
    DEBUG_MARKER,
    ZOOM_TOGGLE,
    CONTROL_SWAP,
    HOME_TOGGLE,
    FULLSCREEN_TOGGLE,
    LOG_PLAY_PAUSE,
    LOG_CLEAR,
    LOG_COPY,
    LOG_SAVE,
    STAT_VIEW_MODE,
    STAT_COPY,
    STAT_SAVE,
    DEBUG_ARCHIVE,
    MENU_ACTION,
    NAVIGATION
}

internal enum class UiHapticKind { LIGHT, CONFIRM }

internal fun hapticKindFor(action: UiHapticAction): UiHapticKind = when (action) {
    UiHapticAction.DEBUG_MARKER,
    UiHapticAction.DEBUG_ARCHIVE -> UiHapticKind.CONFIRM
    else -> UiHapticKind.LIGHT
}

internal fun HapticFeedback.performUiHaptic(action: UiHapticAction) {
    performHapticFeedback(
        when (hapticKindFor(action)) {
            UiHapticKind.LIGHT -> HapticFeedbackType.TextHandleMove
            UiHapticKind.CONFIRM -> HapticFeedbackType.LongPress
        }
    )
}
