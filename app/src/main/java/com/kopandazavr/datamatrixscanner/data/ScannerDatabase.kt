package com.kopandazavr.datamatrixscanner.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

class ScannerDatabase(context: Context) : SQLiteOpenHelper(context, "scanner.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE codes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                raw_bytes BLOB NOT NULL UNIQUE,
                sha256 TEXT NOT NULL,
                is_gs1 INTEGER NOT NULL,
                symbology_identifier TEXT,
                content_type TEXT NOT NULL,
                display_text TEXT NOT NULL,
                gtin TEXT,
                serial TEXT,
                created_at INTEGER NOT NULL,
                last_scan_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                is_duplicate INTEGER NOT NULL DEFAULT 0,
                duplicate_count INTEGER NOT NULL DEFAULT 0,
                is_scanned INTEGER NOT NULL DEFAULT 0,
                batch_id INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_codes_hash ON codes(sha256)")
        db.execSQL("CREATE INDEX idx_codes_status_time ON codes(status, last_scan_at DESC)")
        db.execSQL(
            """CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code_id INTEGER NOT NULL,
                event_at INTEGER NOT NULL,
                type TEXT NOT NULL,
                details TEXT,
                FOREIGN KEY(code_id) REFERENCES codes(id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_events_code_time ON events(code_id, event_at, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

class CodeRepository(context: Context) {
    private val helper = ScannerDatabase(context.applicationContext)

    @Synchronized
    fun scan(
        rawBytes: ByteArray,
        isGs1: Boolean,
        symbologyIdentifier: String?,
        contentType: String,
        fallbackText: String?,
        batchId: Long,
        now: Long = System.currentTimeMillis()
    ): ScanOutcome {
        val hash = MessageDigest.getInstance("SHA-256").digest(rawBytes).toHex()
        val parsed = Gs1Parser.parse(rawBytes, fallbackText)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = findExact(db, hash, rawBytes)
            if (existing == null) {
                val values = ContentValues().apply {
                    put("raw_bytes", rawBytes)
                    put("sha256", hash)
                    put("is_gs1", if (isGs1) 1 else 0)
                    put("symbology_identifier", symbologyIdentifier)
                    put("content_type", contentType)
                    put("display_text", parsed.displayText)
                    put("gtin", parsed.gtin)
                    put("serial", parsed.serial)
                    put("created_at", now)
                    put("last_scan_at", now)
                    put("status", RecordStatus.ACTIVE.name)
                    put("batch_id", batchId)
                }
                val id = db.insertOrThrow("codes", null, values)
                addEvent(db, id, now, EventType.FIRST_SCANNED)
                db.setTransactionSuccessful()
                return ScanOutcome.New(requireNotNull(getById(db, id)))
            }
            if (existing.status == RecordStatus.ACTIVE) {
                db.setTransactionSuccessful()
                return ScanOutcome.IgnoredActive
            }

            val from = existing.status
            val wasArchived = from == RecordStatus.ARCHIVED
            db.execSQL(
                """UPDATE codes SET status=?, last_scan_at=?, is_scanned=0, is_duplicate=?,
                    duplicate_count=duplicate_count+? WHERE id=?""".trimIndent(),
                arrayOf(RecordStatus.ACTIVE.name, now, if (wasArchived) 1 else 0, if (wasArchived) 1 else 0, existing.id)
            )
            addEvent(db, existing.id, now, if (wasArchived) EventType.DUPLICATE_SCANNED else EventType.REPEATED_SCANNED)
            addEvent(
                db,
                existing.id,
                now,
                if (from == RecordStatus.ARCHIVED) EventType.RETURNED_FROM_ARCHIVE else EventType.RETURNED_FROM_TRASH
            )
            db.setTransactionSuccessful()
            return ScanOutcome.Restored(requireNotNull(getById(db, existing.id)), from)
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun list(status: RecordStatus): List<CodeRecord> = helper.readableDatabase.query(
        "codes", null, "status=?", arrayOf(status.name), null, null, "last_scan_at DESC, id DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRecord()) } }

    @Synchronized
    fun get(id: Long): CodeRecord? = getById(helper.readableDatabase, id)

    @Synchronized
    fun events(codeId: Long): List<ScanEvent> = helper.readableDatabase.query(
        "events", null, "code_id=?", arrayOf(codeId.toString()), null, null, "event_at DESC, id DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toEvent()) } }

    @Synchronized
    fun setScanned(id: Long, scanned: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            val record = getById(db, id) ?: return false
            val targetStatus = when {
                scanned -> RecordStatus.ARCHIVED
                record.status == RecordStatus.ARCHIVED -> RecordStatus.TRASH
                else -> record.status
            }
            if (record.isScanned == scanned && record.status == targetStatus) {
                db.setTransactionSuccessful()
                false
            } else {
                db.execSQL(
                    "UPDATE codes SET is_scanned=?, status=?, is_duplicate=? WHERE id=?",
                    arrayOf(if (scanned) 1 else 0, targetStatus.name, if (scanned) if (record.isDuplicate) 1 else 0 else 0, id)
                )
                if (record.isScanned != scanned) {
                    addEvent(db, id, now, if (scanned) EventType.MARKED_SCANNED else EventType.MARKED_UNSCANNED)
                }
                if (record.status != targetStatus) {
                    addEvent(db, id, now, if (targetStatus == RecordStatus.ARCHIVED) EventType.ARCHIVED else EventType.MOVED_TO_TRASH)
                }
                db.setTransactionSuccessful()
                true
            }
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun move(ids: Set<Long>, target: RecordStatus, now: Long = System.currentTimeMillis()) {
        if (ids.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id ->
                val current = getById(db, id) ?: return@forEach
                if (current.status == target) return@forEach
                val scanned = target == RecordStatus.ARCHIVED
                db.execSQL(
                    "UPDATE codes SET status=?, is_scanned=?, is_duplicate=? WHERE id=?",
                    arrayOf(target.name, if (scanned) 1 else 0, if (target == RecordStatus.ARCHIVED && current.isDuplicate) 1 else 0, id)
                )
                if (current.isScanned != scanned) {
                    addEvent(db, id, now, if (scanned) EventType.MARKED_SCANNED else EventType.MARKED_UNSCANNED)
                }
                val event = when (target) {
                    RecordStatus.ARCHIVED -> EventType.ARCHIVED
                    RecordStatus.TRASH -> EventType.MOVED_TO_TRASH
                    RecordStatus.ACTIVE -> if (current.status == RecordStatus.ARCHIVED) {
                        EventType.RETURNED_FROM_ARCHIVE
                    } else EventType.RETURNED_FROM_TRASH
                }
                addEvent(db, id, now, event)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun findExact(db: SQLiteDatabase, hash: String, raw: ByteArray): CodeRecord? {
        db.rawQuery("SELECT * FROM codes WHERE sha256=?", arrayOf(hash)).use { cursor ->
            while (cursor.moveToNext()) {
                val candidate = cursor.toRecord()
                if (candidate.rawBytes.contentEquals(raw)) return candidate
            }
        }
        return null
    }

    private fun getById(db: SQLiteDatabase, id: Long): CodeRecord? = db.query(
        "codes", null, "id=?", arrayOf(id.toString()), null, null, null
    ).use { if (it.moveToFirst()) it.toRecord() else null }

    private fun addEvent(db: SQLiteDatabase, codeId: Long, at: Long, type: EventType, details: String? = null) {
        db.insertOrThrow("events", null, ContentValues().apply {
            put("code_id", codeId)
            put("event_at", at)
            put("type", type.name)
            put("details", details)
        })
    }
}

private fun Cursor.toRecord() = CodeRecord(
    id = getLong(getColumnIndexOrThrow("id")),
    rawBytes = getBlob(getColumnIndexOrThrow("raw_bytes")),
    sha256 = getString(getColumnIndexOrThrow("sha256")),
    isGs1 = getInt(getColumnIndexOrThrow("is_gs1")) != 0,
    symbologyIdentifier = getString(getColumnIndexOrThrow("symbology_identifier")),
    contentType = getString(getColumnIndexOrThrow("content_type")),
    displayText = getString(getColumnIndexOrThrow("display_text")),
    gtin = getString(getColumnIndexOrThrow("gtin")),
    serial = getString(getColumnIndexOrThrow("serial")),
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
    lastScanAt = getLong(getColumnIndexOrThrow("last_scan_at")),
    status = RecordStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
    isDuplicate = getInt(getColumnIndexOrThrow("is_duplicate")) != 0,
    duplicateCount = getInt(getColumnIndexOrThrow("duplicate_count")),
    isScanned = getInt(getColumnIndexOrThrow("is_scanned")) != 0,
    batchId = getLong(getColumnIndexOrThrow("batch_id"))
)

private fun Cursor.toEvent() = ScanEvent(
    id = getLong(getColumnIndexOrThrow("id")),
    codeId = getLong(getColumnIndexOrThrow("code_id")),
    timestamp = getLong(getColumnIndexOrThrow("event_at")),
    type = EventType.valueOf(getString(getColumnIndexOrThrow("type"))),
    details = getString(getColumnIndexOrThrow("details"))
)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
