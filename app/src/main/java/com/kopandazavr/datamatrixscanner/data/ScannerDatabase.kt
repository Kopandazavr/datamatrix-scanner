package com.kopandazavr.datamatrixscanner.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.kopandazavr.datamatrixscanner.scanner.CapturedFrame
import com.kopandazavr.datamatrixscanner.scanner.DetectionBox
import java.security.MessageDigest

class ScannerDatabase(context: Context) : SQLiteOpenHelper(context, "scanner.db", null, 3) {
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
                batch_id INTEGER NOT NULL,
                scan_frame_id INTEGER,
                scan_frame_box TEXT
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
        createRecoveryTable(db)
        createScanFramesTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createRecoveryTable(db)
        if (oldVersion < 3) {
            createScanFramesTable(db)
            db.execSQL("ALTER TABLE codes ADD COLUMN scan_frame_id INTEGER")
            db.execSQL("ALTER TABLE codes ADD COLUMN scan_frame_box TEXT")
        }
    }

    private fun createRecoveryTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS recovery_candidates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                raw_bytes BLOB NOT NULL UNIQUE,
                is_gs1 INTEGER NOT NULL,
                symbology_identifier TEXT,
                content_type TEXT NOT NULL,
                display_text TEXT NOT NULL,
                gtin TEXT,
                serial TEXT,
                detected_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_recovery_time ON recovery_candidates(detected_at DESC, id DESC)")
    }

    private fun createScanFramesTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS scan_frames (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sha256 TEXT NOT NULL UNIQUE,
                jpeg BLOB NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                captured_at INTEGER NOT NULL
            )""".trimIndent()
        )
    }
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
        capturedFrame: CapturedFrame? = null,
        detectionBox: DetectionBox? = null,
        now: Long = System.currentTimeMillis()
    ): ScanOutcome {
        val hash = MessageDigest.getInstance("SHA-256").digest(rawBytes).toHex()
        val parsed = Gs1Parser.parse(rawBytes, fallbackText)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = findExact(db, hash, rawBytes)
            if (existing == null) {
                val frameId = capturedFrame?.let { getOrCreateFrame(db, it, now) }
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
                    put("scan_frame_id", frameId)
                    put("scan_frame_box", detectionBox?.serialize())
                }
                val id = db.insertOrThrow("codes", null, values)
                addEvent(db, id, now, EventType.FIRST_SCANNED)
                db.setTransactionSuccessful()
                return ScanOutcome.New(requireNotNull(getById(db, id)))
            }
            if (existing.status == RecordStatus.ACTIVE) {
                if (existing.scanFrameId == null && capturedFrame != null && detectionBox != null) {
                    val frameId = getOrCreateFrame(db, capturedFrame, now)
                    db.execSQL(
                        "UPDATE codes SET scan_frame_id=?, scan_frame_box=? WHERE id=?",
                        arrayOf<Any>(frameId, detectionBox.serialize(), existing.id)
                    )
                }
                db.setTransactionSuccessful()
                return ScanOutcome.IgnoredActive(requireNotNull(getById(db, existing.id)))
            }

            val from = existing.status
            val wasArchived = from == RecordStatus.ARCHIVED
            val frameId = existing.scanFrameId ?: capturedFrame?.let { getOrCreateFrame(db, it, now) }
            val frameBox = if (existing.scanFrameId == null) detectionBox?.serialize() else null
            val values = ContentValues().apply {
                put("status", RecordStatus.ACTIVE.name)
                put("last_scan_at", now)
                put("batch_id", batchId)
                put("is_scanned", 0)
                put("is_duplicate", if (wasArchived) 1 else 0)
                put("duplicate_count", existing.duplicateCount + if (wasArchived) 1 else 0)
                if (frameId != null) put("scan_frame_id", frameId)
                if (frameBox != null) put("scan_frame_box", frameBox)
            }
            db.update("codes", values, "id=?", arrayOf(existing.id.toString()))
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
    fun activeCount(batchId: Long): Int = helper.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM codes WHERE status=? AND batch_id=?",
        arrayOf(RecordStatus.ACTIVE.name, batchId.toString())
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    @Synchronized
    fun get(id: Long): CodeRecord? = getById(helper.readableDatabase, id)

    @Synchronized
    fun scanFrame(codeId: Long): StoredScanFrame? = helper.readableDatabase.rawQuery(
        """SELECT f.id, f.jpeg, f.width, f.height, c.scan_frame_box
            FROM codes c JOIN scan_frames f ON f.id=c.scan_frame_id WHERE c.id=?""".trimIndent(),
        arrayOf(codeId.toString())
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else StoredScanFrame(
            id = cursor.getLong(0),
            jpeg = cursor.getBlob(1),
            width = cursor.getInt(2),
            height = cursor.getInt(3),
            box = parseFramePoints(cursor.getString(4))
        )
    }

    @Synchronized
    fun addRecoveryCandidates(items: List<com.kopandazavr.datamatrixscanner.scanner.DecodedDataMatrix>, now: Long = System.currentTimeMillis()): Int {
        if (items.isEmpty()) return 0
        val db = helper.writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            items.forEach { item ->
                val parsed = Gs1Parser.parse(item.rawBytes, item.text)
                val result = db.insertWithOnConflict(
                    "recovery_candidates",
                    null,
                    ContentValues().apply {
                        put("raw_bytes", item.rawBytes)
                        put("is_gs1", if (item.isGs1) 1 else 0)
                        put("symbology_identifier", item.symbologyIdentifier)
                        put("content_type", item.contentType)
                        put("display_text", parsed.displayText)
                        put("gtin", parsed.gtin)
                        put("serial", parsed.serial)
                        put("detected_at", now)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                if (result != -1L) added += 1
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    @Synchronized
    fun recoveryCandidates(): List<RecoveryCandidate> = helper.readableDatabase.query(
        "recovery_candidates", null, null, null, null, null, "detected_at DESC, id DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRecoveryCandidate()) } }

    @Synchronized
    fun deleteRecoveryCandidate(id: Long) {
        helper.writableDatabase.delete("recovery_candidates", "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun acceptRecoveryCandidate(id: Long, batchId: Long): ScanOutcome? {
        val candidate = helper.readableDatabase.query(
            "recovery_candidates", null, "id=?", arrayOf(id.toString()), null, null, null
        ).use { if (it.moveToFirst()) it.toRecoveryCandidate() else null } ?: return null
        val outcome = scan(
            rawBytes = candidate.rawBytes,
            isGs1 = candidate.isGs1,
            symbologyIdentifier = candidate.symbologyIdentifier,
            contentType = candidate.contentType,
            fallbackText = candidate.displayText,
            batchId = batchId
        )
        deleteRecoveryCandidate(id)
        return outcome
    }

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
                    arrayOf<Any>(if (scanned) 1 else 0, targetStatus.name, if (scanned) if (record.isDuplicate) 1 else 0 else 0, id)
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
                    arrayOf<Any>(target.name, if (scanned) 1 else 0, if (target == RecordStatus.ARCHIVED && current.isDuplicate) 1 else 0, id)
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

    private fun getOrCreateFrame(db: SQLiteDatabase, frame: CapturedFrame, now: Long): Long {
        db.query("scan_frames", arrayOf("id"), "sha256=?", arrayOf(frame.sha256), null, null, null).use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return db.insertOrThrow("scan_frames", null, ContentValues().apply {
            put("sha256", frame.sha256)
            put("jpeg", frame.jpeg)
            put("width", frame.width)
            put("height", frame.height)
            put("captured_at", now)
        })
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
    batchId = getLong(getColumnIndexOrThrow("batch_id")),
    scanFrameId = getColumnIndex("scan_frame_id").takeIf { it >= 0 && !isNull(it) }?.let(::getLong)
)

private fun Cursor.toEvent() = ScanEvent(
    id = getLong(getColumnIndexOrThrow("id")),
    codeId = getLong(getColumnIndexOrThrow("code_id")),
    timestamp = getLong(getColumnIndexOrThrow("event_at")),
    type = EventType.valueOf(getString(getColumnIndexOrThrow("type"))),
    details = getString(getColumnIndexOrThrow("details"))
)

private fun Cursor.toRecoveryCandidate() = RecoveryCandidate(
    id = getLong(getColumnIndexOrThrow("id")),
    rawBytes = getBlob(getColumnIndexOrThrow("raw_bytes")),
    isGs1 = getInt(getColumnIndexOrThrow("is_gs1")) != 0,
    symbologyIdentifier = getString(getColumnIndexOrThrow("symbology_identifier")),
    contentType = getString(getColumnIndexOrThrow("content_type")),
    displayText = getString(getColumnIndexOrThrow("display_text")),
    gtin = getString(getColumnIndexOrThrow("gtin")),
    serial = getString(getColumnIndexOrThrow("serial")),
    detectedAt = getLong(getColumnIndexOrThrow("detected_at"))
)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun DetectionBox.serialize(): String = points.joinToString(";") { "${it.x},${it.y}" }

private fun parseFramePoints(value: String?): List<FramePoint> = value.orEmpty().split(';').mapNotNull { pair ->
    val parts = pair.split(',')
    if (parts.size != 2) null else {
        val x = parts[0].toFloatOrNull()
        val y = parts[1].toFloatOrNull()
        if (x == null || y == null) null else FramePoint(x, y)
    }
}
