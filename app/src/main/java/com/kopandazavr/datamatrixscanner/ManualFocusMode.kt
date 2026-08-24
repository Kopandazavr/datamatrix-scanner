package com.kopandazavr.datamatrixscanner

internal enum class ManualFocusMode(
    val title: String,
    val maxAttempts: Int,
    val settleDelayMs: Long,
    val nominalProgressMs: Float
) {
    FAST(
        title = "Быстрый",
        maxAttempts = 1,
        settleDelayMs = 0L,
        nominalProgressMs = 650f
    ),
    PRECISE(
        title = "Точный",
        maxAttempts = 2,
        settleDelayMs = 450L,
        nominalProgressMs = 1_800f
    );

    companion object {
        fun fromPreference(value: String?): ManualFocusMode =
            entries.firstOrNull { it.name == value } ?: FAST
    }
}
