package com.kopandazavr.datamatrixscanner

import org.junit.Assert.assertEquals
import org.junit.Test

class UiHapticPolicyTest {
    @Test fun ordinaryControlsStayLight() {
        listOf(
            UiHapticAction.ZOOM_TOGGLE,
            UiHapticAction.CONTROL_SWAP,
            UiHapticAction.HOME_TOGGLE,
            UiHapticAction.FULLSCREEN_TOGGLE,
            UiHapticAction.LOG_PLAY_PAUSE,
            UiHapticAction.LOG_CLEAR,
            UiHapticAction.LOG_COPY,
            UiHapticAction.LOG_SAVE,
            UiHapticAction.STAT_VIEW_MODE,
            UiHapticAction.STAT_COPY,
            UiHapticAction.STAT_SAVE,
            UiHapticAction.NAVIGATION
        ).forEach { assertEquals(UiHapticKind.LIGHT, hapticKindFor(it)) }
    }

    @Test fun markerAndArchiveHaveDistinctConfirmation() {
        assertEquals(UiHapticKind.CONFIRM, hapticKindFor(UiHapticAction.DEBUG_MARKER))
        assertEquals(UiHapticKind.CONFIRM, hapticKindFor(UiHapticAction.DEBUG_ARCHIVE))
    }
}
