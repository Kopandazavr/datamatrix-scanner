package com.kopandazavr.datamatrixscanner.scanner

enum class ScanEnhancementMode(
    val title: String,
    val variantCount: Int
) {
    OFF("Выключено", 0),
    BALANCED("Сбалансированное", 6),
    AGGRESSIVE("Усиленное", 10);

    val decoderAttemptCount: Int get() = variantCount * 2

    companion object {
        fun fromPreference(value: String?): ScanEnhancementMode =
            entries.firstOrNull { it.name == value } ?: BALANCED
    }
}

internal object RescueScanPolicy {
    fun shouldStart(running: Boolean, mode: ScanEnhancementMode): Boolean =
        mode != ScanEnhancementMode.OFF && !running
}

internal fun looksLikeGs1(rawBytes: ByteArray): Boolean {
    val values = rawBytes.map { it.toInt() and 0xff }
    val start = if (values.firstOrNull() == 29) 1 else 0
    if (values.size < start + 18 || values[start] != '0'.code || values[start + 1] != '1'.code) return false
    if ((start + 2 until start + 16).any { values[it] !in '0'.code..'9'.code }) return false
    return values[start + 16] == '2'.code && values[start + 17] == '1'.code
}
