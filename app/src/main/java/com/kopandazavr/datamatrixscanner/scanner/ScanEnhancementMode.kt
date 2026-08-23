package com.kopandazavr.datamatrixscanner.scanner

enum class ScanEnhancementMode(
    val title: String,
    val variantCount: Int,
    val cooldownMs: Long
) {
    OFF("Выключено", 0, Long.MAX_VALUE),
    BALANCED("Сбалансированное", 6, 600L),
    AGGRESSIVE("Усиленное", 10, 800L);

    val decoderAttemptCount: Int get() = variantCount * 2

    companion object {
        fun fromPreference(value: String?): ScanEnhancementMode =
            entries.firstOrNull { it.name == value } ?: BALANCED
    }
}

data class RescueProgress(
    val completed: Int,
    val total: Int
) {
    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total
}

internal object RescueScanPolicy {
    const val STAGNATION_MS = 450L

    fun shouldStart(
        now: Long,
        lastNovelScanAt: Long,
        lastStartedAt: Long,
        running: Boolean,
        mode: ScanEnhancementMode
    ): Boolean = mode != ScanEnhancementMode.OFF &&
        !running &&
        now - lastNovelScanAt >= STAGNATION_MS &&
        now - lastStartedAt >= mode.cooldownMs
}

internal fun looksLikeGs1(rawBytes: ByteArray): Boolean {
    val values = rawBytes.map { it.toInt() and 0xff }
    val start = if (values.firstOrNull() == 29) 1 else 0
    if (values.size < start + 18 || values[start] != '0'.code || values[start + 1] != '1'.code) return false
    if ((start + 2 until start + 16).any { values[it] !in '0'.code..'9'.code }) return false
    return values[start + 16] == '2'.code && values[start + 17] == '1'.code
}
