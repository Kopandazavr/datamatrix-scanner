package com.kopandazavr.datamatrixscanner

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import com.kopandazavr.datamatrixscanner.scanner.AnalyzerSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

data class DebugMarkerReservation(
    val id: String,
    val sequence: Int,
    val wallMs: Long,
    val elapsedMs: Long,
    val uiFileName: String,
    val sourceFileName: String,
    val metadataFileName: String
)

data class DebugMarkerFinalize(
    val uiStatus: String,
    val sourceStatus: String,
    val frameId: Long? = null,
    val frameElapsedMs: Long? = null,
    val sensorTimestampNs: Long? = null,
    val runtimeState: String = ""
)

/** Private, bounded marker storage. Nothing here is registered in MediaStore/gallery. */
class DebugMarkerStore(private val context: android.content.Context) {
    private val directory = File(context.filesDir, "debug_markers")
    private val prefs = context.getSharedPreferences("debug_marker_store", 0)
    private val nextSequence = AtomicInteger(prefs.getInt("next_sequence", 0))
    private val ioLock = Any()

    fun reserve(): DebugMarkerReservation {
        val sequence = nextSequence.incrementAndGet()
        prefs.edit().putInt("next_sequence", sequence).apply()
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val stamp = FILE_TIME.get().format(Date(wall))
        val id = "M${sequence.toString().padStart(5, '0')}_$stamp"
        return DebugMarkerReservation(
            id = id,
            sequence = sequence,
            wallMs = wall,
            elapsedMs = elapsed,
            uiFileName = "${id}_ui.jpg",
            sourceFileName = "${id}_source.jpg",
            metadataFileName = "${id}.json"
        )
    }

    suspend fun captureUi(activity: Activity, marker: DebugMarkerReservation): String = withContext(Dispatchers.Main.immediate) {
        val decor = activity.window.decorView
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) return@withContext "missing:window_not_laid_out"
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            .getOrElse { return@withContext "missing:bitmap_${it.javaClass.simpleName}" }
        val result = suspendCancellableCoroutine<Int> { continuation ->
            runCatching {
                PixelCopy.request(activity.window, bitmap, { code ->
                    if (continuation.isActive) continuation.resume(code)
                }, Handler(Looper.getMainLooper()))
            }.onFailure {
                if (continuation.isActive) continuation.resume(PixelCopy.ERROR_UNKNOWN)
            }
            continuation.invokeOnCancellation { bitmap.recycle() }
        }
        if (result != PixelCopy.SUCCESS) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return@withContext "missing:pixelcopy_$result"
        }
        val saved = saveBitmap(marker.uiFileName, bitmap, quality = 86)
        if (!bitmap.isRecycled) bitmap.recycle()
        if (saved) "saved:${marker.uiFileName}" else "missing:write_failed"
    }

    suspend fun saveSource(marker: DebugMarkerReservation, snapshot: AnalyzerSnapshot?): String {
        if (snapshot == null) return "missing:no_analysis_frame"
        val saved = saveBitmap(marker.sourceFileName, snapshot.bitmap, quality = 92)
        if (!snapshot.bitmap.isRecycled) snapshot.bitmap.recycle()
        return if (saved) "saved:${marker.sourceFileName}" else "missing:write_failed"
    }

    suspend fun finalize(marker: DebugMarkerReservation, details: DebugMarkerFinalize) = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            directory.mkdirs()
            val json = JSONObject()
                .put("schemaVersion", 1)
                .put("id", marker.id)
                .put("sequence", marker.sequence)
                .put("wallMs", marker.wallMs)
                .put("elapsedMs", marker.elapsedMs)
                .put("uiFile", marker.uiFileName)
                .put("uiStatus", details.uiStatus)
                .put("sourceFile", marker.sourceFileName)
                .put("sourceStatus", details.sourceStatus)
                .put("frameId", details.frameId ?: JSONObject.NULL)
                .put("frameElapsedMs", details.frameElapsedMs ?: JSONObject.NULL)
                .put("sensorTimestampNs", details.sensorTimestampNs ?: JSONObject.NULL)
                .put("runtimeState", details.runtimeState)
            File(directory, marker.metadataFileName).writeText(json.toString(2), Charsets.UTF_8)
            enforceRetentionLocked()
        }
    }

    suspend fun clear(): Int = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            val files = directory.listFiles().orEmpty()
            files.count { runCatching { it.delete() }.getOrDefault(false) }
        }
    }

    suspend fun retainedFiles(): List<File> = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            enforceRetentionLocked()
            directory.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName)
        }
    }

    suspend fun indexCsv(): String = withContext(Dispatchers.IO) {
        val header = "id,sequence,wallMs,elapsedMs,frameId,frameElapsedMs,sensorTimestampNs,uiFile,uiStatus,sourceFile,sourceStatus"
        val rows = synchronized(ioLock) {
            directory.listFiles { file -> file.extension == "json" }.orEmpty()
                .sortedBy(File::getName)
                .mapNotNull { file ->
                    runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()?.let { json ->
                        listOf(
                            json.optString("id"),
                            json.optInt("sequence").toString(),
                            json.optLong("wallMs").toString(),
                            json.optLong("elapsedMs").toString(),
                            json.optNullableLong("frameId"),
                            json.optNullableLong("frameElapsedMs"),
                            json.optNullableLong("sensorTimestampNs"),
                            json.optString("uiFile"),
                            json.optString("uiStatus"),
                            json.optString("sourceFile"),
                            json.optString("sourceStatus")
                        ).joinToString(",") { csv(it) }
                    }
                }
        }
        (listOf(header) + rows).joinToString("\n")
    }

    private suspend fun saveBitmap(name: String, bitmap: Bitmap, quality: Int): Boolean = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            directory.mkdirs()
            runCatching {
                val target = File(directory, name)
                target.outputStream().buffered().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream))
                }
                true
            }.getOrDefault(false)
        }
    }

    private fun enforceRetentionLocked() {
        if (!directory.exists()) return
        val files = directory.listFiles().orEmpty().filter(File::isFile)
        val groups = files.groupBy(::markerIdFromFileName)
            .filterKeys(String::isNotBlank)
            .map { (id, groupFiles) -> MarkerFileGroup(id, groupFiles, groupFiles.minOfOrNull(File::lastModified) ?: 0L) }
            .sortedBy(MarkerFileGroup::oldestModifiedMs)
            .toMutableList()

        while (groups.size > MAX_MARKERS || directorySize() > MAX_BYTES) {
            val oldest = groups.removeFirstOrNull() ?: break
            oldest.files.forEach { runCatching { it.delete() } }
        }

        // Files that cannot be associated with a marker ID (for example a crash-corrupted temp)
        // are not allowed to bypass the byte cap indefinitely.
        if (directorySize() > MAX_BYTES) {
            directory.listFiles().orEmpty().filter(File::isFile).sortedBy(File::lastModified).forEach { file ->
                if (directorySize() <= MAX_BYTES) return@forEach
                runCatching { file.delete() }
            }
        }
    }

    private fun markerIdFromFileName(file: File): String {
        val name = file.name
        val metadataId = if (name.endsWith(".json")) name.removeSuffix(".json") else null
        if (metadataId != null && MARKER_ID_REGEX.matches(metadataId)) return metadataId
        return MARKER_ID_REGEX.find(name)?.value.orEmpty()
    }

    private data class MarkerFileGroup(
        val id: String,
        val files: List<File>,
        val oldestModifiedMs: Long
    )

    private fun directorySize(): Long = directory.listFiles().orEmpty().filter(File::isFile).sumOf(File::length)

    companion object {
        private const val MAX_MARKERS = 24
        private const val MAX_BYTES = 48L * 1024L * 1024L
        private val MARKER_ID_REGEX = Regex("M\\d{5}_\\d{8}_\\d{6}_\\d{3}")
        private val FILE_TIME = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US) }
    }
}

private fun JSONObject.optNullableLong(key: String): String =
    if (!has(key) || isNull(key)) "" else optLong(key).toString()

private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
