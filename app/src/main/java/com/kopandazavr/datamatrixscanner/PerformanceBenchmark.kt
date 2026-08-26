package com.kopandazavr.datamatrixscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.datamatrix.DataMatrixWriter
import com.kopandazavr.datamatrixscanner.scanner.ImageVariantFactory
import com.kopandazavr.datamatrixscanner.scanner.VariantKind
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import zxingcpp.BarcodeReader

internal enum class BenchmarkViewMode { SHORT, FULL }

internal sealed interface PerformanceBenchmarkState {
    data object Idle : PerformanceBenchmarkState
    data class Running(
        val phase: String,
        val progress: Float,
        val workers: Int,
        val cancellable: Boolean = true
    ) : PerformanceBenchmarkState
    data class Completed(val result: PerformanceBenchmarkResult) : PerformanceBenchmarkState
    data class Failed(val message: String) : PerformanceBenchmarkState
}

internal data class BenchmarkLevelMetrics(
    val workers: Int,
    val iterations: Int,
    val correct: Int,
    val errors: Int,
    val jobsPerSecond: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val preprocessP95Ms: Double,
    val firstDecodeMs: Double,
    val batchMakespanMs: Long,
    val heapDeltaBytes: Long,
    val itemDiagnostics: List<BenchmarkItemDiagnostic> = emptyList()
) {
    fun policyResult() = WorkerLevelResult(workers, jobsPerSecond, p95Ms, errors, correct, iterations)
}

internal data class ThermalSnapshot(
    val status: Int?,
    val headroom: Float?,
    val cpuHeadroom: Double?
)

internal data class BenchmarkDecoderDiagnostic(
    val decoder: String,
    val decoded: String?,
    val correct: Boolean,
    val error: String?,
    val durationMs: Double
)

internal data class BenchmarkItemDiagnostic(
    val itemId: String,
    val expected: String?,
    val variant: String,
    val correct: Boolean,
    val error: Boolean,
    val zxing: BenchmarkDecoderDiagnostic,
    val mlKit: BenchmarkDecoderDiagnostic
)

internal data class PerformanceBenchmarkResult(
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val corpusVersion: String,
    val pipelineVersion: String,
    val device: String,
    val os: String,
    val logicalCores: Int,
    val cameraStageMs: Double?,
    val cameraStageSkipped: Boolean,
    val warmupMs: Long,
    val levels: List<BenchmarkLevelMetrics>,
    val recommendedWorkers: Int,
    val recommendationValid: Boolean,
    val recommendationFallbackReason: String?,
    val sustainedJobsPerSecond: Double,
    val startThermal: ThermalSnapshot,
    val endThermal: ThermalSnapshot,
    val cancelled: Boolean = false
) {
    fun format(mode: BenchmarkViewMode): String = buildString {
        appendLine("Data Matrix Scanner — тест производительности")
        appendLine("pipeline=$pipelineVersion corpus=$corpusVersion")
        appendLine("device=$device os=$os cores=$logicalCores")
        appendLine("recommendedWorkers=$recommendedWorkers recommendationValid=${if (recommendationValid) 1 else 0} sustainedJobsPerSec=${fmt(sustainedJobsPerSecond)}")
        if (!recommendationValid) {
            appendLine("Калибровка не состоялась: ${recommendationFallbackReason ?: "неизвестная причина"}.")
            appendLine("recommendedWorkers=$recommendedWorkers — fallback; production worker policy не изменена.")
        }
        appendLine("cameraStage=${cameraStageMs?.let(::fmt) ?: "skipped"} ms cancelled=${if (cancelled) 1 else 0}")
        appendLine("thermal start=${thermal(startThermal)} end=${thermal(endThermal)}")
        if (mode == BenchmarkViewMode.FULL) {
            appendLine("started=$startedAtMs finished=$finishedAtMs warmupMs=$warmupMs")
            appendLine("workers iterations correct errors jobs/s p50 p95 p99 prepP95 firstDecode makespan heapDelta")
            levels.forEach { level ->
                appendLine(
                    "${level.workers} ${level.iterations} ${level.correct} ${level.errors} " +
                        "${fmt(level.jobsPerSecond)} ${fmt(level.p50Ms)} ${fmt(level.p95Ms)} ${fmt(level.p99Ms)} " +
                        "${fmt(level.preprocessP95Ms)} ${fmt(level.firstDecodeMs)} ${level.batchMakespanMs} ${level.heapDeltaBytes}"
                )
                level.itemDiagnostics.forEach { item ->
                    appendLine(
                        "BENCH_ITEM workers=${level.workers} id=${item.itemId} expected=${item.expected ?: "<negative>"} " +
                            "variant=${item.variant} correct=${if (item.correct) 1 else 0} error=${if (item.error) 1 else 0} " +
                            "zxing=${item.zxing.decoded ?: "<none>"}/${if (item.zxing.correct) "ok" else "miss"}/${item.zxing.error ?: "-"}/${fmt(item.zxing.durationMs)}ms " +
                            "mlkit=${item.mlKit.decoded ?: "<none>"}/${if (item.mlKit.correct) "ok" else "miss"}/${item.mlKit.error ?: "-"}/${fmt(item.mlKit.durationMs)}ms"
                    )
                }
            }
        }
    }

    private fun thermal(value: ThermalSnapshot): String =
        "status=${value.status ?: -1},headroom=${value.headroom?.let { fmt(it.toDouble()) } ?: "na"},cpu=${value.cpuHeadroom?.let { fmt(it) } ?: "na"}"

    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
}

internal class ForegroundPerformanceBenchmark(
    private val context: Context,
    private val logger: PipelineDebugLogger? = null
) {
    private data class CorpusItem(
        val id: String,
        val bitmap: Bitmap,
        val expected: String?,
        val variant: VariantKind
    )
    private data class JobResult(
        val correct: Boolean,
        val error: Boolean,
        val durationMs: Double,
        val preprocessMs: Double,
        val decoded: Boolean,
        val diagnostic: BenchmarkItemDiagnostic
    )
    private data class Worker(
        val executor: java.util.concurrent.ExecutorService,
        val scanner: BarcodeScanner,
        val reader: BarcodeReader
    )

    private val cancelled = AtomicBoolean(false)

    fun cancel() { cancelled.set(true) }

    fun resetCancellation() { cancelled.set(false) }

    fun run(
        cameraSnapshot: Bitmap?,
        pipelineVersion: String,
        onProgress: (PerformanceBenchmarkState.Running) -> Unit
    ): PerformanceBenchmarkResult {
        resetCancellation()
        val startedAt = System.currentTimeMillis()
        val startThermal = thermalSnapshot()
        val corpus = createCorpus()
        var cameraStageMs: Double? = null
        val levels = mutableListOf<BenchmarkLevelMetrics>()
        var warmupMs = 0L
        try {
            onProgress(PerformanceBenchmarkState.Running("camera/copy", .03f, 0))
            cameraStageMs = cameraSnapshot?.let { bitmap ->
                val started = SystemClock.elapsedRealtimeNanos()
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                val variant = ImageVariantFactory.create(copy, VariantKind.CONTRAST_135)
                if (variant.owned) variant.bitmap.recycle()
                copy.recycle()
                (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            }

            onProgress(PerformanceBenchmarkState.Running("warm-up", .08f, 1, cancellable = false))
            val warmStarted = SystemClock.elapsedRealtime()
            runLevel(corpus.take(2), 1, warmup = true)
            warmupMs = SystemClock.elapsedRealtime() - warmStarted

            val ceiling = minOf(Runtime.getRuntime().availableProcessors().coerceAtLeast(1) * 2, 16)
            for (workers in 1..ceiling) {
                if (cancelled.get()) break
                val fraction = .10f + .68f * workers / ceiling.coerceAtLeast(1)
                onProgress(PerformanceBenchmarkState.Running("peak sweep", fraction, workers))
                val mix = buildList {
                    addAll(corpus)
                    addAll(corpus.take(4))
                }
                val metrics = runLevel(mix, workers, warmup = false)
                levels += metrics
                logger?.log(
                    "BENCH_LEVEL",
                    "workers" to workers,
                    "jobsPerSec" to metrics.jobsPerSecond,
                    "p95" to metrics.p95Ms,
                    "correct" to metrics.correct,
                    "errors" to metrics.errors
                )
                if (shouldStopWorkerSweep(levels.map(BenchmarkLevelMetrics::policyResult))) break
            }

            val recommendedLevel = recommendedWorkerLevel(levels.map(BenchmarkLevelMetrics::policyResult))
            val recommended = recommendedLevel?.workers ?: 1
            onProgress(PerformanceBenchmarkState.Running("sustained", .84f, recommended))
            val sustainedMix = buildList { repeat(3) { addAll(corpus) } }
            val sustained = if (cancelled.get()) null else runLevel(sustainedMix, recommended, warmup = false)
            if (sustained != null) levels += sustained
            val fallbackReason = benchmarkFallbackReason(
                cancelled = cancelled.get(),
                peakRecommendation = recommendedLevel,
                sustained = sustained?.policyResult()
            )
            val recommendationValid = fallbackReason == null
            onProgress(PerformanceBenchmarkState.Running("finalize", .98f, recommended, cancellable = false))

            return PerformanceBenchmarkResult(
                startedAtMs = startedAt,
                finishedAtMs = System.currentTimeMillis(),
                corpusVersion = CORPUS_VERSION,
                pipelineVersion = pipelineVersion,
                device = "${Build.MANUFACTURER}/${Build.MODEL}/${Build.SUPPORTED_ABIS.joinToString(",")}",
                os = "Android ${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}",
                logicalCores = Runtime.getRuntime().availableProcessors(),
                cameraStageMs = cameraStageMs,
                cameraStageSkipped = cameraSnapshot == null,
                warmupMs = warmupMs,
                levels = levels,
                recommendedWorkers = recommended,
                recommendationValid = recommendationValid,
                recommendationFallbackReason = fallbackReason,
                sustainedJobsPerSecond = sustained?.jobsPerSecond ?: levels.lastOrNull()?.jobsPerSecond ?: 0.0,
                startThermal = startThermal,
                endThermal = thermalSnapshot(),
                cancelled = cancelled.get()
            )
        } finally {
            cameraSnapshot?.recycle()
            corpus.forEach { it.bitmap.recycle() }
        }
    }

    private fun runLevel(items: List<CorpusItem>, workerCount: Int, warmup: Boolean): BenchmarkLevelMetrics {
        val workers = List(workerCount.coerceAtLeast(1)) { index ->
            Worker(
                executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "dms-bench-$workerCount-$index") },
                scanner = BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX).build()
                ),
                reader = BarcodeReader(
                    BarcodeReader.Options(
                        formats = setOf(BarcodeReader.Format.DATA_MATRIX),
                        tryHarder = true,
                        tryRotate = true,
                        tryInvert = true,
                        tryDownscale = true,
                        maxNumberOfSymbols = 4,
                        textMode = BarcodeReader.TextMode.PLAIN
                    )
                )
            )
        }
        val heapBefore = usedHeap()
        val batchStarted = SystemClock.elapsedRealtime()
        val futures = mutableListOf<Future<JobResult>>()
        try {
            items.forEachIndexed { index, item ->
                if (cancelled.get() && !warmup) return@forEachIndexed
                val worker = workers[index % workers.size]
                futures += worker.executor.submit(Callable { runJob(worker, item, workerCount) })
            }
            val results = futures.mapNotNull { future ->
                runCatching { future.get(JOB_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            }
            val makespan = (SystemClock.elapsedRealtime() - batchStarted).coerceAtLeast(1L)
            val durations = results.map { it.durationMs }.sorted()
            val preprocess = results.map { it.preprocessMs }.sorted()
            val firstDecode = results.indexOfFirst(JobResult::decoded).takeIf { it >= 0 }
                ?.let { durations.getOrNull(it) } ?: makespan.toDouble()
            return BenchmarkLevelMetrics(
                workers = workerCount,
                iterations = futures.size,
                correct = results.count(JobResult::correct),
                errors = results.count(JobResult::error) + (futures.size - results.size),
                jobsPerSecond = results.size * 1_000.0 / makespan,
                p50Ms = percentile(durations, .50),
                p95Ms = percentile(durations, .95),
                p99Ms = percentile(durations, .99),
                preprocessP95Ms = percentile(preprocess, .95),
                firstDecodeMs = firstDecode,
                batchMakespanMs = makespan,
                heapDeltaBytes = usedHeap() - heapBefore,
                itemDiagnostics = results.map(JobResult::diagnostic)
            )
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
            workers.forEach { worker ->
                worker.scanner.close()
                worker.executor.shutdownNow()
            }
        }
    }

    private fun runJob(worker: Worker, item: CorpusItem, workerCount: Int): JobResult {
        val started = SystemClock.elapsedRealtimeNanos()
        val preprocessStarted = SystemClock.elapsedRealtimeNanos()
        val variant = ImageVariantFactory.create(item.bitmap, item.variant)
        val preprocessMs = (SystemClock.elapsedRealtimeNanos() - preprocessStarted) / 1_000_000.0
        return try {
            val zxingStarted = SystemClock.elapsedRealtimeNanos()
            var zxingError: String? = null
            val zxingText = runCatching {
                worker.reader.read(variant.bitmap).firstOrNull { it.bytes != null && it.error == null }?.text
            }.onFailure { zxingError = it.javaClass.simpleName }.getOrNull()
            val zxingMs = (SystemClock.elapsedRealtimeNanos() - zxingStarted) / 1_000_000.0
            val mlStarted = SystemClock.elapsedRealtimeNanos()
            var mlError: String? = null
            val mlText = runCatching {
                Tasks.await(
                    worker.scanner.process(InputImage.fromBitmap(variant.bitmap, 0)),
                    ML_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                ).firstOrNull { it.format == Barcode.FORMAT_DATA_MATRIX }?.rawValue
            }.onFailure { mlError = it.javaClass.simpleName }.getOrNull()
            val mlMs = (SystemClock.elapsedRealtimeNanos() - mlStarted) / 1_000_000.0
            val decoded = zxingText != null || mlText != null
            val correct = if (item.expected == null) !decoded else zxingText == item.expected || mlText == item.expected
            val zxingCorrect = if (item.expected == null) zxingText == null else zxingText == item.expected
            val mlCorrect = if (item.expected == null) mlText == null else mlText == item.expected
            val diagnostic = BenchmarkItemDiagnostic(
                itemId = item.id, expected = item.expected, variant = item.variant.name,
                correct = correct, error = zxingError != null || mlError != null,
                zxing = BenchmarkDecoderDiagnostic("ZXING_CPP", zxingText, zxingCorrect, zxingError, zxingMs),
                mlKit = BenchmarkDecoderDiagnostic("ML_KIT", mlText, mlCorrect, mlError, mlMs)
            )
            logger?.log(
                "BENCH_ITEM", "workers" to workerCount, "item" to item.id, "expected" to item.expected,
                "variant" to item.variant.name, "correct" to if (correct) 1 else 0,
                "zxing" to zxingText, "zxingCorrect" to if (zxingCorrect) 1 else 0, "zxingError" to zxingError,
                "mlkit" to mlText, "mlkitCorrect" to if (mlCorrect) 1 else 0, "mlkitError" to mlError
            )
            JobResult(
                correct = correct,
                error = diagnostic.error,
                durationMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                preprocessMs = preprocessMs,
                decoded = decoded,
                diagnostic = diagnostic
            )
        } catch (error: Throwable) {
            val failedDecoder = BenchmarkDecoderDiagnostic("FAILED", null, false, error.javaClass.simpleName, 0.0)
            JobResult(
                false, true, (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                preprocessMs, false,
                BenchmarkItemDiagnostic(item.id, item.expected, item.variant.name, false, true, failedDecoder, failedDecoder)
            )
        } finally {
            if (variant.owned) variant.bitmap.recycle()
        }
    }

    private fun createCorpus(): List<CorpusItem> {
        val writer = DataMatrixWriter()
        fun code(id: String, size: Int, foreground: Int, background: Int, rotate: Float = 0f): CorpusItem {
            val matrix = writer.encode(id, BarcodeFormat.DATA_MATRIX, size, size)
            val pixels = IntArray(size * size) { index ->
                if (matrix[index % size, index / size]) foreground else background
            }
            val raw = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
            val bitmap = if (rotate == 0f) raw else Bitmap.createBitmap(
                raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotate) }, true
            ).also { if (it !== raw) raw.recycle() }
            return CorpusItem(id, bitmap, id, if (foreground == Color.BLACK) VariantKind.ORIGINAL else VariantKind.CLAHE)
        }
        val negative = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { bitmap ->
            val pixels = IntArray(bitmap.width * bitmap.height) { index ->
                val x = index % bitmap.width
                val y = index / bitmap.width
                Color.rgb((x * 17 + y * 13) and 0xff, (x * 7 + y * 19) and 0xff, (x * 11 + y * 5) and 0xff)
            }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        return listOf(
            code("DMS-BENCH-1", 128, Color.BLACK, Color.WHITE),
            code("DMS-BENCH-2", 192, Color.BLACK, Color.WHITE, 90f),
            code("DMS-BENCH-3", 256, Color.rgb(45, 55, 95), Color.rgb(175, 165, 140)),
            code("DMS-BENCH-4", 144, Color.rgb(60, 60, 60), Color.rgb(165, 165, 165)),
            code("DMS-BENCH-5", 220, Color.BLACK, Color.WHITE, 180f),
            CorpusItem("negative", negative, null, VariantKind.ORIGINAL)
        )
    }

    private fun thermalSnapshot(): ThermalSnapshot {
        val power = context.getSystemService(PowerManager::class.java)
        val status = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null
        val headroom = if (Build.VERSION.SDK_INT >= 30) {
            runCatching { power?.getThermalHeadroom(0) }.getOrNull()?.takeIf { it.isFinite() }
        } else null
        return ThermalSnapshot(status, headroom, readCpuHeadroomReflectively())
    }

    private fun readCpuHeadroomReflectively(): Double? {
        if (Build.VERSION.SDK_INT < 36) return null
        return runCatching {
            val serviceName = Context::class.java.getField("SYSTEM_HEALTH_SERVICE").get(null) as String
            val manager = context.getSystemService(serviceName) ?: return@runCatching null
            val method = manager.javaClass.methods.firstOrNull { it.name == "getCpuHeadroom" && it.parameterCount == 0 }
                ?: return@runCatching null
            (method.invoke(manager) as? Number)?.toDouble()
        }.getOrNull()
    }

    private fun usedHeap(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        return sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    }

    companion object {
        private const val CORPUS_VERSION = "dms-corpus-1"
        private const val ML_TIMEOUT_MS = 1_500L
        private const val JOB_TIMEOUT_MS = 3_000L
    }
}
