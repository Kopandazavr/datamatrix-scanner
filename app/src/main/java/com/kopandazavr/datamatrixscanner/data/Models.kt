package com.kopandazavr.datamatrixscanner.data

enum class RecordStatus { ACTIVE, ARCHIVED, TRASH }

enum class EventType(val displayName: String) {
    FIRST_SCANNED("Отсканировано впервые"),
    REPEATED_SCANNED("Отсканировано повторно"),
    DUPLICATE_SCANNED("Отсканирован как дубликат"),
    MARKED_SCANNED("Отмечен как отсканированный"),
    MARKED_UNSCANNED("Снята отметка «Отсканировано»"),
    ARCHIVED("Архивировано"),
    MOVED_TO_TRASH("Перемещено в корзину"),
    RETURNED_FROM_ARCHIVE("Возвращён из архива"),
    RETURNED_FROM_TRASH("Возвращён из корзины")
}

data class CodeRecord(
    val id: Long,
    val rawBytes: ByteArray,
    val sha256: String,
    val isGs1: Boolean,
    val symbologyIdentifier: String?,
    val contentType: String,
    val displayText: String,
    val gtin: String?,
    val serial: String?,
    val createdAt: Long,
    val lastScanAt: Long,
    val status: RecordStatus,
    val isDuplicate: Boolean,
    val duplicateCount: Int,
    val isScanned: Boolean,
    val batchId: Long
)

data class ScanEvent(
    val id: Long,
    val codeId: Long,
    val timestamp: Long,
    val type: EventType,
    val details: String?
)

sealed interface ScanOutcome {
    data class New(val record: CodeRecord) : ScanOutcome
    data class Restored(val record: CodeRecord, val from: RecordStatus) : ScanOutcome
    data object IgnoredActive : ScanOutcome
}

data class ParsedGs1(val gtin: String?, val serial: String?, val displayText: String)
