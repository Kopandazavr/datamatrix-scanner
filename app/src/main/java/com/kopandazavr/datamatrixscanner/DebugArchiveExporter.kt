package com.kopandazavr.datamatrixscanner

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.kopandazavr.datamatrixscanner.scanner.PipelineDiagnostics
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DebugArchiveRuntimeSnapshot(
    val debugEnabled: Boolean,
    val fullscreen: Boolean,
    val previewZoomedIn: Boolean,
    val controlsMirrored: Boolean,
    val enhancementMode: String,
    val manualFocusMode: String,
    val currentBatchId: Long,
    val recognizedCount: Int,
    val cameraAvailable: Boolean,
    val diagnostics: PipelineDiagnostics,
    val focus: FocusControlSnapshot,
    val focusMetadata: FocusLensMetadata?
) {
    fun format(): String = buildString {
        appendLine("debugEnabled=${if (debugEnabled) 1 else 0}")
        appendLine("fullscreen=${if (fullscreen) 1 else 0}")
        appendLine("previewZoomedIn=${if (previewZoomedIn) 1 else 0}")
        appendLine("controlsMirrored=${if (controlsMirrored) 1 else 0}")
        appendLine("enhancementMode=$enhancementMode")
        appendLine("manualFocusMode=$manualFocusMode")
        appendLine("batchId=$currentBatchId recognizedCount=$recognizedCount cameraAvailable=${if (cameraAvailable) 1 else 0}")
        appendLine("pipeline fast=${diagnostics.fastJobs} mlPending=${diagnostics.mlPending} mlInFlight=${diagnostics.mlInFlight} heavyPending=${diagnostics.heavyPending} heavyInFlight=${diagnostics.heavyInFlight} oldestAgeMs=${diagnostics.oldestFrameAgeMs} frameId=${diagnostics.currentFrameId}")
        appendLine("pipeline roiProduction=+40% candidateWorkers=2 analysisTarget=1280x720")
        appendLine("pipeline zxing=android-3.1.1 mlKit=barcode-scanning-17.3.0 mlFormats=DATA_MATRIX mlPotentialBarcodes=1")
        appendLine("focus owner=${focus.owner} requested=${focus.requestedDistance} actual=${focus.actualDistance} HOME=${focus.homeDistance} minFocus=${focus.minimumFocusDistance} actualStale=${if (focus.actualStale) 1 else 0} generation=${focus.generation}")
        focusMetadata?.let { metadata ->
            appendLine("focusMetadata sensorTsNs=${metadata.sensorTimestampNs} frame=${metadata.frameNumber} actual=${metadata.actualDistance} lensState=${FocusLensMetadataMonitor.lensStateName(metadata.lensState)} afMode=${FocusLensMetadataMonitor.afModeName(metadata.afMode)} afState=${FocusLensMetadataMonitor.afStateName(metadata.afState)} aeState=${metadata.aeState}")
            appendLine("focusMetadata exposureNs=${metadata.exposureTimeNs} iso=${metadata.iso} physical=${metadata.physicalCameraId} cropRegion=${metadata.cropRegion} afRegions=${metadata.afRegions}")
        } ?: appendLine("focusMetadata=missing:no_capture_result_yet")
    }
}

internal class DebugArchiveExporter(
    private val context: Context,
    private val logger: PipelineDebugLogger,
    private val statistics: ScannerStatisticsStore,
    private val markerStore: DebugMarkerStore
) {
    private val persistedDirectory = File(context.filesDir, "debug_artifacts")

    suspend fun storeLastBenchmark(fullReport: String) = withContext(Dispatchers.IO) {
        persistedDirectory.mkdirs()
        File(persistedDirectory, LAST_BENCHMARK_FILE).writeText(fullReport, Charsets.UTF_8)
    }

    suspend fun build(runtime: DebugArchiveRuntimeSnapshot): File = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "debug_archive_staging").apply {
            deleteRecursively()
            mkdirs()
        }
        val missing = mutableListOf<String>()
        val generatedAt = System.currentTimeMillis()

        writeText(staging, "statistics_full.txt", statistics.format(StatisticsViewMode.FULL))

        val recording = logger.snapshotText()
        if (recording.isBlank()) missing += "recording_log.txt: no non-cleared recording log lines"
        else writeText(staging, "recording_log.txt", recording)

        val session = logger.snapshotSessionText()
        if (session.isBlank()) missing += "session_log.txt: no retained app-session log lines"
        else writeText(staging, "session_log.txt", session)

        val combinedLogs = listOf(recording, session).filter(String::isNotBlank).joinToString("\n")
        val warnError = combinedLogs.lineSequence().filter { line ->
            line.contains(" WARN ") || line.contains(" ERROR ") || line.contains("APP WARN ") || line.contains("APP ERROR ")
        }.joinToString("\n")
        if (warnError.isBlank()) missing += "warn_error_extract.txt: no WARN/ERROR lines in retained logs"
        else writeText(staging, "warn_error_extract.txt", warnError)

        val boostRoi = combinedLogs.lineSequence().filter { line ->
            listOf("BOOST_", "ROI_BENCH", "BOOST_MATRIX", "BOOST_VARIANT", "EVIDENCE").any(line::contains)
        }.joinToString("\n")
        if (boostRoi.isBlank()) missing += "pipeline_artifacts/boost_roi_variant_log_extract.txt: no retained Boost/ROI/variant events; archive does not trigger them"
        else writeText(staging, "pipeline_artifacts/boost_roi_variant_log_extract.txt", boostRoi)

        writeText(staging, "runtime_snapshot.txt", runtime.format())
        val capabilityLines = combinedLogs.lineSequence().filter { line ->
            line.contains("FOCUS_CAMERA_INFO") || line.contains("FOCUS_BIND_OFF") || line.contains("FOCUS_CAPTURE_RESULT")
        }.toList().takeLast(120).joinToString("\n")
        writeText(staging, "camera_focus_capabilities_and_state.txt", buildString {
            appendLine("The full Statistics report is the persistent capability source (camera id, hardware level, minFocus, AF modes, max AF regions).")
            appendLine(runtime.format().lineSequence().filter { it.startsWith("focus") || it.startsWith("pipeline") }.joinToString("\n"))
            if (capabilityLines.isNotBlank()) {
                appendLine("recentCapabilityTelemetry:")
                appendLine(capabilityLines)
            } else {
                appendLine("recentCapabilityTelemetry=missing:no retained focus-capability events")
            }
        })

        val markerFiles = markerStore.retainedFiles()
        if (markerFiles.isEmpty()) {
            missing += "markers/: no retained diagnostic markers"
        } else {
            markerFiles.forEach { source -> copyFile(source, File(staging, "markers/${source.name}")) }
            writeText(staging, "markers/index.csv", markerStore.indexCsv())
        }

        val persistedArtifacts = persistedDirectory.listFiles().orEmpty().filter {
            it.isFile && it.name != LAST_BENCHMARK_FILE && it.length() > 0L
        }
        if (persistedArtifacts.isEmpty()) {
            missing += "pipeline_artifacts/persisted/: no persisted Boost/ROI/variant diagnostic artifacts"
        } else {
            persistedArtifacts.forEach { source ->
                copyFile(source, File(staging, "pipeline_artifacts/persisted/${source.name}"))
            }
        }

        val benchmark = File(persistedDirectory, LAST_BENCHMARK_FILE)
        if (benchmark.isFile && benchmark.length() > 0L) {
            copyFile(benchmark, File(staging, "benchmark/$LAST_BENCHMARK_FILE"))
        } else {
            missing += "benchmark/$LAST_BENCHMARK_FILE: no completed benchmark persisted; archive does not start benchmark automatically"
        }

        val entriesBeforeManifest = staging.walkTopDown().filter(File::isFile).map { file ->
            val relative = file.relativeTo(staging).invariantSeparatorsPath
            Triple(relative, file.length(), sha256(file))
        }.sortedBy { it.first }.toList()

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        val manifest = buildString {
            appendLine("Data Matrix Scanner — debug archive manifest")
            appendLine("schemaVersion=1")
            appendLine("generatedAtMs=$generatedAt")
            appendLine("app=${packageInfo.versionName}/$versionCode package=${context.packageName}")
            appendLine("device=${Build.MANUFACTURER}/${Build.MODEL} api=${Build.VERSION.SDK_INT} os=${Build.VERSION.RELEASE}")
            appendLine("archivePolicy=no benchmark/Boost/ROI work was started by export; export does not clear logs or marker assets")
            appendLine("entries:")
            entriesBeforeManifest.forEach { (name, size, hash) -> appendLine("  $name size=$size sha256=$hash") }
            appendLine("missing:")
            if (missing.isEmpty()) appendLine("  none") else missing.forEach { appendLine("  $it") }
        }
        writeText(staging, "manifest.txt", manifest)

        val exportDir = File(context.cacheDir, "debug_exports").apply { mkdirs() }
        exportDir.listFiles().orEmpty().filter { it.isFile && System.currentTimeMillis() - it.lastModified() > EXPORT_RETENTION_MS }
            .forEach { runCatching { it.delete() } }
        val stamp = FILE_TIME.get().format(Date(generatedAt))
        val version = packageInfo.versionName.orEmpty().ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val zip = File(exportDir, "DataMatrix_debug_archive_v${version}_$stamp.zip")
        zipDirectory(staging, zip)
        staging.deleteRecursively()
        zip
    }

    private fun writeText(root: File, relative: String, text: String) {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    private fun copyFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }

    private fun zipDirectory(root: File, target: File) {
        ZipOutputStream(FileOutputStream(target).buffered()).use { zip ->
            root.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
                val name = file.relativeTo(root).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(name))
                FileInputStream(file).buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val LAST_BENCHMARK_FILE = "last_benchmark_full.txt"
        private const val EXPORT_RETENTION_MS = 3L * 24L * 60L * 60L * 1000L
        private val FILE_TIME = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }
    }
}

internal fun shareDebugArchive(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.debugfiles", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Передать отладочный архив"))
}
