package com.kopandazavr.datamatrixscanner

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DebugLogState(
    val recording: Boolean = false,
    val sessionId: Long? = null,
    val lineCount: Int = 0,
    val droppedEvents: Int = 0,
    val rotatedLines: Long = 0L
)

class PipelineDebugLogger(private val context: Context) : AutoCloseable {
    private data class Pending(
        val sessionId: Long,
        val relMs: Long,
        val wallMs: Long,
        val event: String,
        val fields: List<Pair<String, Any?>>
    )

    private data class RoiStats(
        var total: Int = 0,
        var success: Int = 0,
        val latencies: MutableList<Long> = mutableListOf()
    )

    private data class SessionStats(
        val startedAt: Long,
        var framesReceived: Int = 0,
        var framesProcessed: Int = 0,
        var framesSkipped: Int = 0,
        var framesDropped: Int = 0,
        var candidates: Int = 0,
        var mlPasses: Int = 0,
        var decodeZxing: Int = 0,
        var decodeMl: Int = 0,
        var decodeHeavy: Int = 0,
        var maxQueue: Int = 0,
        var maxFrameAge: Long = 0L,
        var warnings: Int = 0,
        var errors: Int = 0,
        val mlLatencies: MutableList<Long> = mutableListOf(),
        val roi: MutableMap<String, RoiStats> = linkedMapOf()
    )

    private val pending = ArrayBlockingQueue<Pending>(2048)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dms-debug-log").apply { isDaemon = true }
    }
    private val lock = Any()
    private val lines = ArrayDeque<String>(MAX_LINES)
    private data class AppSessionLine(val elapsedMs: Long, val text: String)
    private val sessionLock = Any()
    private val sessionLines = ArrayDeque<AppSessionLine>(SESSION_MAX_LINES)
    private val appSessionStartedAt = SystemClock.elapsedRealtime()
    private val nextSession = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private val _state = MutableStateFlow(DebugLogState())
    val state: StateFlow<DebugLogState> = _state.asStateFlow()
    private val _lineCount = MutableStateFlow(0)
    val lineCount: StateFlow<Int> = _lineCount.asStateFlow()
    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()
    private val _linesVersion = MutableStateFlow(0L)
    val linesVersion: StateFlow<Long> = _linesVersion.asStateFlow()
    private val _sessionLineCount = MutableStateFlow(0)
    val sessionLineCount: StateFlow<Int> = _sessionLineCount.asStateFlow()
    private val _sessionLinesVersion = MutableStateFlow(0L)
    val sessionLinesVersion: StateFlow<Long> = _sessionLinesVersion.asStateFlow()
    private var rotatedLines = 0L
    private var reportedDropped = 0

    @Volatile private var activeSession: Long? = null
    @Volatile private var sessionStartedAt = 0L
    @Volatile private var stats: SessionStats? = null
    @Volatile private var closed = false

    val isRecording: Boolean get() = activeSession != null

    init {
        appendSessionEvent(
            "APP_SESSION_START",
            listOf(
                "app" to appVersion(),
                "device" to "${Build.MANUFACTURER}/${Build.MODEL}",
                "api" to Build.VERSION.SDK_INT
            )
        )
        worker.execute {
            while (!closed || pending.isNotEmpty()) {
                val item = runCatching { pending.poll(250L, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
                append(format(item))
            }
        }
    }

    fun startSession(vararg headerFields: Pair<String, Any?>): Long {
        if (isRecording) stopSession("restart")
        val id = nextSession.incrementAndGet().toLong()
        val now = SystemClock.elapsedRealtime()
        sessionStartedAt = now
        stats = SessionStats(now)
        activeSession = id
        dropped.set(0)
        reportedDropped = 0
        _state.value = _state.value.copy(recording = true, sessionId = id, droppedEvents = 0)
        _activeSessionId.value = id
        log(
            "SESSION_START",
            "app" to appVersion(),
            "device" to "${Build.MANUFACTURER}/${Build.MODEL}",
            "api" to Build.VERSION.SDK_INT,
            "roiProduction" to "+40%",
            "candidateWorkers" to 2,
            *headerFields
        )
        return id
    }

    fun startSession(headerFields: Map<String, Any?>): Long =
        startSession(*headerFields.entries.map { it.key to it.value }.toTypedArray())

    fun stopSession(reason: String = "pause") {
        val id = activeSession ?: return
        val now = SystemClock.elapsedRealtime()
        val s = stats
        if (s != null) {
            val sorted = s.mlLatencies.sorted()
            val summaryFields = listOf(
                "reason" to reason,
                "durationMs" to (now - s.startedAt),
                "frames" to "${s.framesReceived}/${s.framesProcessed}/${s.framesSkipped}/${s.framesDropped}",
                "candidates" to s.candidates,
                "mlPasses" to s.mlPasses,
                "decode" to "zx=${s.decodeZxing},ml=${s.decodeMl},heavy=${s.decodeHeavy}",
                "maxQueue" to s.maxQueue,
                "maxAgeMs" to s.maxFrameAge,
                "mlP50" to percentile(sorted, .50),
                "mlP95" to percentile(sorted, .95),
                "mlMax" to (sorted.maxOrNull() ?: 0L),
                "warn" to s.warnings,
                "error" to s.errors,
                "logDropped" to dropped.get(),
                "rotated" to rotatedLines,
                "roi" to roiSummary(s)
            )
            appendSessionEvent("SESSION_STOP/SUMMARY", summaryFields)
            enqueue(
                Pending(
                    id,
                    now - sessionStartedAt,
                    System.currentTimeMillis(),
                    "SESSION_STOP/SUMMARY",
                    summaryFields
                )
            )
        }
        activeSession = null
        stats = null
        _state.value = _state.value.copy(recording = false, sessionId = null, droppedEvents = dropped.get(), rotatedLines = rotatedLines)
        _activeSessionId.value = null
    }

    fun clear() {
        pending.clear()
        synchronized(lock) { lines.clear() }
        rotatedLines = 0L
        reportedDropped = 0
        _lineCount.value = 0
        _linesVersion.value = _linesVersion.value + 1L
        _state.value = _state.value.copy(lineCount = 0, droppedEvents = 0, rotatedLines = 0L)
        dropped.set(0)
    }

    fun snapshotLines(): List<String> = synchronized(lock) { lines.toList() }
    fun snapshotText(): String = synchronized(lock) { lines.joinToString("\n") }

    fun clearSessionLogs() {
        synchronized(sessionLock) { sessionLines.clear() }
        _sessionLineCount.value = 0
        _sessionLinesVersion.value = _sessionLinesVersion.value + 1L
    }

    fun snapshotSessionLines(): List<String> {
        val now = SystemClock.elapsedRealtime()
        val snapshot = synchronized(sessionLock) {
            pruneSessionLocked(now)
            sessionLines.map { it.text }
        }
        _sessionLineCount.value = snapshot.size
        return snapshot
    }

    fun snapshotSessionText(): String = snapshotSessionLines().joinToString("\n")

    fun log(event: String, vararg fields: Pair<String, Any?>) {
        val fieldList = fields.toList()
        if (shouldKeepInAppSession(event, fieldList)) appendSessionEvent(event, fieldList)
        val id = activeSession ?: return
        val now = SystemClock.elapsedRealtime()
        updateStats(event, fieldList)
        enqueue(Pending(id, now - sessionStartedAt, System.currentTimeMillis(), event, fieldList))
    }

    fun log(event: String, fields: Map<String, Any?>) = log(event, *fields.entries.map { it.key to it.value }.toTypedArray())

    fun warn(message: String, vararg fields: Pair<String, Any?>) = log("WARN", "message" to message, *fields)
    fun error(message: String, vararg fields: Pair<String, Any?>) = log("ERROR", "message" to message, *fields)

    private fun enqueue(item: Pending) {
        if (!pending.offer(item)) {
            val count = dropped.incrementAndGet()
            _state.value = _state.value.copy(droppedEvents = count)
        }
    }

    private fun append(line: String) {
        val currentDropped = dropped.get()
        val count = synchronized(lock) {
            if (currentDropped > reportedDropped) {
                appendRingLine("LOG_DROPPED count=$currentDropped")
                reportedDropped = currentDropped
            }
            appendRingLine(line)
            lines.size
        }
        _lineCount.value = count
        _linesVersion.value = _linesVersion.value + 1L
        _state.value = _state.value.copy(lineCount = count, droppedEvents = currentDropped, rotatedLines = rotatedLines)
    }

    private fun appendRingLine(line: String) {
        if (lines.size >= MAX_LINES) {
            lines.removeFirst()
            rotatedLines++
            if (rotatedLines == 1L || rotatedLines % 500L == 0L) {
                if (lines.size >= MAX_LINES) lines.removeFirst()
                lines.addLast("LOG_ROTATED removed=$rotatedLines limit=$MAX_LINES")
            }
        }
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(line)
    }

    private fun shouldKeepInAppSession(event: String, fields: List<Pair<String, Any?>>): Boolean {
        fun field(name: String): String? = fields.firstOrNull { it.first == name }?.second?.toString()
        if (event == "FOCUS_LENS_REQUEST") return field("phase") != "user_slider"
        if (event == "FOCUS_HOLD_APPLY") return field("reason") != "user_slider"
        if (event == "DECODE") return field("route") != "IgnoredActiveCached"
        return event in SESSION_EVENT_ALLOWLIST
    }

    private fun appendSessionEvent(event: String, fields: List<Pair<String, Any?>>) {
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val rel = String.format(Locale.US, "+%.3fs", (now - appSessionStartedAt) / 1000.0)
        val fieldText = fields.joinToString(" ") { (key, value) -> "$key=${sanitize(value)}" }
        val raw = buildString {
            append(WALL_FORMAT.get().format(Date(wall))).append(' ').append(rel).append(" APP ").append(event)
            if (fieldText.isNotEmpty()) append(' ').append(fieldText)
        }
        val line = if (raw.length <= SESSION_MAX_LINE_CHARS) raw else raw.take(SESSION_MAX_LINE_CHARS - 1) + "…"
        val count = synchronized(sessionLock) {
            pruneSessionLocked(now)
            if (sessionLines.size >= SESSION_MAX_LINES) sessionLines.removeFirst()
            sessionLines.addLast(AppSessionLine(now, line))
            sessionLines.size
        }
        _sessionLineCount.value = count
        _sessionLinesVersion.value = _sessionLinesVersion.value + 1L
    }

    private fun pruneSessionLocked(now: Long) {
        val cutoff = now - SESSION_RETENTION_MS
        while (sessionLines.isNotEmpty() && sessionLines.first().elapsedMs < cutoff) sessionLines.removeFirst()
    }

    private fun updateStats(event: String, fields: List<Pair<String, Any?>>) {
        val s = stats ?: return
        fun long(name: String): Long? = fields.firstOrNull { it.first == name }?.second?.toString()?.toLongOrNull()
        when (event) {
            "FRAME_RECEIVED" -> s.framesReceived++
            "FRAME_PROCESS", "PROCESS" -> s.framesProcessed++
            "FRAME_SKIP", "SKIP" -> s.framesSkipped++
            "DROP", "FRAME_DROP" -> s.framesDropped++
            "CANDIDATE" -> s.candidates++
            "ML_START" -> s.mlPasses++
            "ML_END" -> long("run")?.let(s.mlLatencies::add) ?: long("runMs")?.let(s.mlLatencies::add)
            "ROI_BENCH" -> {
                val margin = fields.firstOrNull { it.first == "margin" }?.second?.toString() ?: "?"
                val roi = s.roi.getOrPut(margin) { RoiStats() }
                roi.total++
                if (long("success") == 1L) roi.success++
                long("run")?.let(roi.latencies::add)
            }
            "WARN" -> s.warnings++
            "ERROR" -> s.errors++
            "DECODE" -> {
                val source = fields.firstOrNull { it.first == "source" }?.second?.toString().orEmpty().lowercase()
                when {
                    source.contains("heavy") || source.contains("rescue") -> s.decodeHeavy++
                    source.contains("zxing") -> s.decodeZxing++
                    else -> s.decodeMl++
                }
            }
        }
        long("queue")?.let { s.maxQueue = maxOf(s.maxQueue, it.toInt()) }
        (long("age") ?: long("ageMs"))?.let { s.maxFrameAge = maxOf(s.maxFrameAge, it) }
    }

    private fun format(item: Pending): String {
        val wall = WALL_FORMAT.get().format(Date(item.wallMs))
        val rel = String.format(Locale.US, "+%.3fs", item.relMs / 1000.0)
        val fields = item.fields.joinToString(" ") { (key, value) -> "$key=${sanitize(value)}" }
        return buildString {
            append(wall).append(' ').append(rel).append(" S#").append(item.sessionId).append(' ').append(item.event)
            if (fields.isNotEmpty()) append(' ').append(fields)
        }
    }

    private fun sanitize(value: Any?): String = when (value) {
        null -> "-"
        is Float -> String.format(Locale.US, "%.3f", value)
        is Double -> String.format(Locale.US, "%.3f", value)
        else -> value.toString().replace(' ', '_').replace('\n', '_')
    }

    private fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${info.versionName}/$code"
    }.getOrDefault("unknown")

    private fun roiSummary(s: SessionStats): String = s.roi.entries.joinToString(";") { (margin, value) ->
        val sorted = value.latencies.sorted()
        "$margin:n=${value.total},ok=${value.success},p50=${percentile(sorted, .50)},p95=${percentile(sorted, .95)}"
    }.ifEmpty { "none" }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0L
        val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    override fun close() {
        stopSession("app_close")
        closed = true
        worker.shutdown()
    }

    companion object {
        private const val MAX_LINES = 8000
        private const val SESSION_RETENTION_MS = 10L * 60L * 1000L
        private const val SESSION_MAX_LINES = 1200
        private const val SESSION_MAX_LINE_CHARS = 900
        private val SESSION_EVENT_ALLOWLIST = setOf(
            "SESSION_START", "SESSION_STOP/SUMMARY",
            "UI_EVENT", "DECODE", "WARN", "ERROR",
            "BENCH_START", "BENCH_END", "BENCH_CANCEL",
            "USER_MARKER", "MARKER_ARTIFACT", "DEBUG_ARCHIVE",
            "FOCUS_BIND_OFF", "FOCUS_HOME_SCOPE", "FOCUS_HOME", "FOCUS_PARK",
            "FOCUS_BUSY", "FOCUS_HAPTIC", "FOCUS_REQUEST", "FOCUS_AUTO_TRIGGER",
            "FOCUS_START", "FOCUS_END", "FOCUS_SWEEP_SKIP", "FOCUS_SWEEP_START",
            "FOCUS_EARLY_KEEP_CURRENT", "FOCUS_EARLY_CONFIRM", "FOCUS_SWEEP_TURN",
            "FOCUS_SWEEP_FALLBACK", "FOCUS_SWEEP_CANCELLED", "FOCUS_TIMEOUT_FINALIZE",
            "FOCUS_RECOVERY", "FOCUS_AUTO_START", "FOCUS_AF_EXPAND", "FOCUS_AUTO_END",
            "FOCUS_NATIVE_ROLLBACK", "FOCUS_NATIVE_ROLLBACK_END",
            "FOCUS_HOLD_APPLY", "FOCUS_SWEEP_END", "FOCUS_CAMERA_INFO",
            "HEAVY_STAGE", "BOOST_START", "BOOST_EVIDENCE_WINDOW", "BOOST_END", "BOOST_SKIP"
        )
        private val WALL_FORMAT = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

        fun shortPayloadId(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
