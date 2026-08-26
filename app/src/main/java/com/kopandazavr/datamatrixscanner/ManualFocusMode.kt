package com.kopandazavr.datamatrixscanner

internal enum class ManualFocusMode(
    val title: String,
    val coarseSegments: Int,
    val finePass: Boolean,
    val nominalProgressMs: Float
) {
    FAST(
        title = "Быстрый",
        coarseSegments = 4,
        finePass = false,
        nominalProgressMs = 1_100f
    ),
    PRECISE(
        title = "Точный",
        coarseSegments = 7,
        finePass = true,
        nominalProgressMs = 2_100f
    );

    companion object {
        fun fromPreference(value: String?): ManualFocusMode =
            entries.firstOrNull { it.name == value } ?: PRECISE
    }
}
