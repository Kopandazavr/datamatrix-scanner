package com.kopandazavr.datamatrixscanner.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.kopandazavr.datamatrixscanner.PipelineDebugLogger
import com.kopandazavr.datamatrixscanner.PerformanceStageEvent
import com.kopandazavr.datamatrixscanner.ScannerStatisticsStore
import com.kopandazavr.datamatrixscanner.VariantPortfolioEvent
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import zxingcpp.BarcodeReader

internal data class BoostRunResult(
    val submitted: Int,
    val completed: Int,
    val decoded: Int,
    val skipped: Int,
    val reason: String
)

/**
 * Live ML Kit path for ZXing-C++ candidate boxes.
 *
 * Production decoding intentionally keeps the proven +40% crop until phone benchmark
 * data says otherwise. The latency change in this build is bounded parallelism: two
 * independent ML Kit workers can decode different visible candidates at once instead
 * of serializing every crop behind the first one.
 */
internal class LiveCandidateProcessor(
    private val onDecoded: (List<DecodedDataMatrix>) -> Unit,
    private val onPotentialBoxes: (List<DetectionBox>) -> Unit,
    private val logger: PipelineDebugLogger? = null,
    private val statistics: ScannerStatisticsStore? = null,
    private val evidenceTracker: CandidateEvidenceTracker,
    private val onDiagnostics: (pending: Int, inFlight: Int, oldestAgeMs: Long, frameId: Long) -> Unit = { _, _, _, _ -> }
) : AutoCloseable {
    private data class Worker(
        val executor: java.util.concurrent.ExecutorService,
        val scanner: BarcodeScanner
    )
    private data class CandidateFuture(
        val binding: CandidateEvidenceBinding,
        val region: RecoveryRegion,
        val future: Future<LiveGooglePass?>
    )
    private data class BoostRoi(
        val identity: CandidateIdentity,
        val bitmap: Bitmap,
        val frameId: Long,
        val frameElapsedMs: Long,
        val offsetX: Float,
        val offsetY: Float,
        val fullWidth: Int,
        val fullHeight: Int,
        val quality: Float
    )

    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val latest = AtomicReference<LiveCandidateBatch?>(null)
    private val coordinator = Executors.newSingleThreadExecutor { r -> Thread(r, "dms-live-ml-coordinator") }
    private val workers = List(2) { index ->
        Worker(
            Executors.newSingleThreadExecutor { r -> Thread(r, "dms-live-ml-$index") },
            newScanner()
        )
    }
    private val fullScanner = newScanner()
    private val inFlight = AtomicInteger(0)
    private val nextWorker = AtomicInteger(0)
    private val boostRunning = AtomicBoolean(false)
    private val boostExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2),
        { runnable -> Thread(runnable, "dms-boost-expensive") },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val boostScanner = newScanner()
    private val boostHardReader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.DATA_MATRIX), tryHarder = true,
            tryRotate = true, tryInvert = true, tryDownscale = true,
            maxNumberOfSymbols = 16, textMode = BarcodeReader.TextMode.PLAIN
        )
    )
    private val roiLock = Any()
    private val roiRing = object : LinkedHashMap<CandidateIdentity, ArrayDeque<BoostRoi>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CandidateIdentity, ArrayDeque<BoostRoi>>?): Boolean {
            if (size <= BOOST_IDENTITY_LIMIT || eldest == null) return false
            eldest.value.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            return true
        }
    }
    @Volatile var latestFrameWidth: Int = 1
        private set
    @Volatile var latestFrameHeight: Int = 1
        private set
    @Volatile private var pendingJobs = 0
    @Volatile private var oldestFrameStartedAt = 0L
    @Volatile private var currentFrameId = 0L

    /** Always takes ownership of [source]. */
    fun submit(
        source: Bitmap,
        seedRegions: List<RecoveryRegion>,
        evidenceBindings: List<CandidateEvidenceBinding>,
        frameId: Long,
        frameStartedAt: Long
    ) {
        if (closed.get()) {
            source.recycle()
            return
        }
        latestFrameWidth = source.width
        latestFrameHeight = source.height
        rememberBoostRois(source, evidenceBindings, frameId, frameStartedAt)
        val batch = LiveCandidateBatch(source, seedRegions, evidenceBindings, frameId, frameStartedAt)
        val replaced = latest.getAndSet(batch)
        if (replaced != null) {
            replaced.source.recycle()
            logger?.log(
                "DROP",
                "frame" to replaced.frameId,
                "reason" to "candidate_batch_replaced",
                "age" to (SystemClock.elapsedRealtime() - replaced.frameStartedAt)
            )
        }
        publishDiagnostics(frameId, frameStartedAt)
        schedule()
    }

    private fun schedule() {
        if (!closed.get() && running.compareAndSet(false, true)) coordinator.execute(::drain)
    }

    private fun drain() {
        try {
            while (!closed.get()) {
                val batch = latest.getAndSet(null) ?: break
                process(batch)
            }
        } finally {
            running.set(false)
            publishDiagnostics(currentFrameId, oldestFrameStartedAt)
            if (!closed.get() && latest.get() != null) schedule()
        }
    }

    private fun process(batch: LiveCandidateBatch) {
        val source = batch.source
        currentFrameId = batch.frameId
        oldestFrameStartedAt = batch.frameStartedAt
        val accumulated = LinkedHashMap<String, DecodedDataMatrix>()
        var capturedFrame: CapturedFrame? = null

        fun publish(pass: LiveGooglePass?) {
            if (pass == null || pass.decoded.isEmpty()) return
            if (capturedFrame == null) capturedFrame = source.toLiveCapturedFrame()
            var changed = false
            pass.decoded.forEach { item ->
                val key = Base64.encodeToString(item.rawBytes, Base64.NO_WRAP)
                val enriched = capturedFrame?.let { item.copy(capturedFrame = it) } ?: item
                if (accumulated.putIfAbsent(key, enriched) == null) changed = true
            }
            if (changed) onDecoded(accumulated.values.toList())
        }

        try {
            // [batch.seedRegions] was already merged in DataMatrixAnalyzer and its list index is the
            // candidateIndex stored by CandidateEvidenceTracker. Re-merging here used to sort by
            // area, silently changing indexes and pairing an ROI with another track/generation.
            // Preserve that identity all the way into ML and Boost.
            val seedCandidates = bindLiveCandidates(batch.seedRegions, batch.evidenceBindings, limit = 12)
            val seedRegions = seedCandidates.map(BoundLiveCandidate::region)
            val futures = mutableListOf<CandidateFuture>()
            pendingJobs = seedCandidates.size
            publishDiagnostics(batch.frameId, batch.frameStartedAt)

            seedCandidates.forEach { candidate ->
                val index = candidate.candidateIndex
                val region = candidate.region
                val binding = candidate.binding
                val candidateId = "${batch.frameId}:$index"
                val worker = workers[Math.floorMod(nextWorker.getAndIncrement(), workers.size)]
                logger?.log(
                    "CANDIDATE",
                    "frame" to batch.frameId,
                    "candidate" to candidateId,
                    "track" to binding.trackId,
                    "generation" to binding.generation,
                    "roi" to region.compact(),
                    "queue" to pendingJobs
                )
                val queuedAt = SystemClock.elapsedRealtime()
                val future = worker.executor.submit(Callable {
                    decodeRegion(
                        scanner = worker.scanner,
                        source = source,
                        region = region,
                        padding = CANDIDATE_CROP_PADDING,
                        frameId = batch.frameId,
                        frameStartedAt = batch.frameStartedAt,
                        candidateId = candidateId,
                        passName = "candidate+40",
                        queuedAtElapsedMs = queuedAt
                    )
                })
                futures += CandidateFuture(binding, region, future)
            }

            // Publish whichever candidate finishes first rather than waiting for input order.
            val remaining = futures.toMutableList()
            val rawPasses = linkedMapOf<Int, LiveGooglePass?>()
            while (remaining.isNotEmpty() && !closed.get()) {
                var madeProgress = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val pending = iterator.next()
                    if (!pending.future.isDone) continue
                    val pass = runCatching { pending.future.get() }.getOrNull()
                    rawPasses[pending.binding.trackId] = pass
                    statistics?.recordVariantPortfolio(
                        VariantPortfolioEvent(
                            variant = VariantKind.ORIGINAL.name,
                            success = pass?.decoded?.isNotEmpty() == true,
                            trackConfirmed = pending.binding.eligibleForBoost,
                            rawFailed = pass?.decoded?.isEmpty() != false,
                            preprocessMs = pass?.preprocessMs,
                            decoderMs = pass?.runMs?.toDouble()
                        )
                    )
                    publish(pass)
                    iterator.remove()
                    pendingJobs = remaining.size
                    publishDiagnostics(batch.frameId, batch.frameStartedAt)
                    madeProgress = true
                }
                if (!madeProgress) Thread.sleep(4L)
            }

            // Keep the existing single original full-frame pass after priority candidate crops.
            val fullPass = decodeWithGoogle(
                scanner = fullScanner,
                bitmap = source,
                frameId = batch.frameId,
                frameStartedAt = batch.frameStartedAt,
                candidateId = "full",
                passName = "full"
            )
            publish(fullPass)

            val allRegions = mergeRecoveryRegions(
                seedRegions + fullPass.regions,
                source.width,
                source.height,
                maxRegions = 12
            )
            onPotentialBoxes(
                allRegions.mapIndexed { index, region ->
                    region.toPotentialDetectionBox(source.width, source.height, "live-google:$index")
                }
            )

            // Google potential-only regions are lower priority; skip ones already covered.
            val extra = mergeRecoveryRegions(fullPass.regions, source.width, source.height, maxRegions = 8)
                .filter { google -> seedRegions.none { seed -> overlapRatio(seed, google) >= .68f } }
            pendingJobs = extra.size
            publishDiagnostics(batch.frameId, batch.frameStartedAt)
            extra.forEachIndexed { index, region ->
                val worker = workers[Math.floorMod(nextWorker.getAndIncrement(), workers.size)]
                val queuedAt = SystemClock.elapsedRealtime()
                val pass = runCatching {
                    worker.executor.submit(Callable {
                        decodeRegion(
                            worker.scanner,
                            source,
                            region,
                            CANDIDATE_CROP_PADDING,
                            batch.frameId,
                            batch.frameStartedAt,
                            "${batch.frameId}:g$index",
                            "potential+40",
                            queuedAt
                        )
                    }).get()
                }.getOrNull()
                publish(pass)
                pendingJobs = (pendingJobs - 1).coerceAtLeast(0)
                publishDiagnostics(batch.frameId, batch.frameStartedAt)
            }

        } catch (t: Throwable) {
            logger?.error("live_candidate", "frame" to batch.frameId, "error" to t.javaClass.simpleName)
        } finally {
            pendingJobs = 0
            oldestFrameStartedAt = 0L
            publishDiagnostics(batch.frameId, 0L)
            source.recycle()
        }
    }

    private fun decodeRegion(
        scanner: BarcodeScanner,
        source: Bitmap,
        region: RecoveryRegion,
        padding: Float,
        frameId: Long,
        frameStartedAt: Long,
        candidateId: String,
        passName: String,
        queuedAtElapsedMs: Long
    ): LiveGooglePass? {
        val cropStarted = SystemClock.elapsedRealtimeNanos()
        // ML Kit has a Data Matrix-specific correctness constraint: the code must intersect
        // the exact centre of the input image. The old paddedSquare() shifted edge crops back
        // inside the sensor frame, which moved the candidate away from the ML input centre.
        // Keep a virtual square centred on the candidate and pad only the out-of-frame part.
        val crop = centeredMlCrop(source, region, padding) ?: return null
        val cropMs = (SystemClock.elapsedRealtimeNanos() - cropStarted) / 1_000_000.0
        logger?.log(
            "CROP",
            "frame" to frameId,
            "candidate" to candidateId,
            "margin" to "${(padding * 100).toInt()}%",
            "roi" to region.compact(),
            "origin" to "${crop.originX},${crop.originY}",
            "size" to "${crop.bitmap.width}x${crop.bitmap.height}",
            "centerErrorPx" to crop.centerErrorPx,
            "edgePadded" to if (crop.edgePadded) 1 else 0,
            "run" to cropMs
        )
        return try {
            decodeWithGoogle(
                scanner,
                crop.bitmap,
                offsetX = crop.originX.toFloat(),
                offsetY = crop.originY.toFloat(),
                fullWidth = source.width,
                fullHeight = source.height,
                frameId = frameId,
                frameStartedAt = frameStartedAt,
                candidateId = candidateId,
                passName = passName,
                queuedAtElapsedMs = queuedAtElapsedMs
            ).copy(preprocessMs = cropMs).also { pass ->
                statistics?.recordPerformanceStage(
                    PerformanceStageEvent(
                        preprocessMs = cropMs,
                        decoderMs = pass.runMs.toDouble(),
                        endToEndMs = (SystemClock.elapsedRealtime() - frameStartedAt).coerceAtLeast(0L).toDouble()
                    )
                )
            }
        } finally {
            crop.bitmap.recycle()
        }
    }

    private fun decodeVariantRegion(
        scanner: BarcodeScanner,
        source: Bitmap,
        region: RecoveryRegion,
        variantKind: VariantKind,
        frameId: Long,
        frameStartedAt: Long,
        candidateId: String,
        queuedAtElapsedMs: Long
    ): LiveGooglePass? {
        val preprocessStarted = SystemClock.elapsedRealtimeNanos()
        val crop = centeredMlCrop(source, region, CANDIDATE_CROP_PADDING) ?: return null
        val variant = try {
            ImageVariantFactory.create(crop.bitmap, variantKind)
        } catch (t: Throwable) {
            crop.bitmap.recycle()
            throw t
        }
        val preprocessMs = (SystemClock.elapsedRealtimeNanos() - preprocessStarted) / 1_000_000.0
        return try {
            decodeWithGoogle(
                scanner = scanner,
                bitmap = variant.bitmap,
                offsetX = crop.originX.toFloat(),
                offsetY = crop.originY.toFloat(),
                fullWidth = source.width,
                fullHeight = source.height,
                frameId = frameId,
                frameStartedAt = frameStartedAt,
                candidateId = candidateId,
                passName = "portfolio:${variantKind.name.lowercase()}",
                queuedAtElapsedMs = queuedAtElapsedMs
            ).copy(preprocessMs = preprocessMs).also { pass ->
                statistics?.recordPerformanceStage(
                    PerformanceStageEvent(
                        preprocessMs = preprocessMs,
                        decoderMs = pass.runMs.toDouble(),
                        endToEndMs = (SystemClock.elapsedRealtime() - frameStartedAt).coerceAtLeast(0L).toDouble()
                    )
                )
            }
        } finally {
            if (variant.owned) variant.bitmap.recycle()
            crop.bitmap.recycle()
        }
    }

    private fun decodeWithGoogle(
        scanner: BarcodeScanner,
        bitmap: Bitmap,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        fullWidth: Int = bitmap.width,
        fullHeight: Int = bitmap.height,
        frameId: Long,
        frameStartedAt: Long,
        candidateId: String,
        passName: String,
        queuedAtElapsedMs: Long = SystemClock.elapsedRealtime()
    ): LiveGooglePass {
        val started = SystemClock.elapsedRealtime()
        val wait = (started - queuedAtElapsedMs).coerceAtLeast(0L)
        val frameAgeAtStart = (started - frameStartedAt).coerceAtLeast(0L)
        inFlight.incrementAndGet()
        publishDiagnostics(frameId, frameStartedAt)
        logger?.log(
            "ML_START",
            "frame" to frameId,
            "candidate" to candidateId,
            "pass" to passName,
            "size" to "${bitmap.width}x${bitmap.height}",
            "wait" to wait,
            "age" to frameAgeAtStart,
            "queue" to pendingJobs
        )

        val barcodes = try {
            Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), ML_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            val run = SystemClock.elapsedRealtime() - started
            logger?.log(
                "ML_END",
                "frame" to frameId,
                "candidate" to candidateId,
                "pass" to passName,
                "run" to run,
                "age" to (SystemClock.elapsedRealtime() - frameStartedAt),
                "result" to "error:${t.javaClass.simpleName}"
            )
            return LiveGooglePass(emptyList(), emptyList(), runMs = run)
        } finally {
            inFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
            publishDiagnostics(frameId, frameStartedAt)
        }

        val decoded = mutableListOf<DecodedDataMatrix>()
        val regions = mutableListOf<RecoveryRegion>()
        var dataMatrixDetections = 0
        var payloadDetections = 0
        var rawValueFallbacks = 0
        barcodes.forEach { barcode ->
            val localPoints = barcode.cornerPoints?.takeIf { it.size >= 4 }?.take(4)
                ?: barcode.boundingBox?.let { box ->
                    listOf(
                        Point(box.left, box.top), Point(box.right, box.top),
                        Point(box.right, box.bottom), Point(box.left, box.bottom)
                    )
                }
                ?: return@forEach
            localPoints.toLiveRegion(offsetX, offsetY)?.let(regions::add)
            if (barcode.format != Barcode.FORMAT_DATA_MATRIX) return@forEach
            dataMatrixDetections++
            val rawBytes = barcode.rawBytes
            val bytes = decodedBarcodeBytes(rawBytes, barcode.rawValue) ?: return@forEach
            payloadDetections++
            if (rawBytes == null) rawValueFallbacks++
            val points = localPoints.map { point ->
                NormalizedPoint(
                    ((offsetX + point.x) / fullWidth.coerceAtLeast(1)).coerceIn(0f, 1f),
                    ((offsetY + point.y) / fullHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
                )
            }
            val isGs1 = looksLikeGs1(bytes)
            decoded += DecodedDataMatrix(
                rawBytes = bytes,
                text = barcode.rawValue,
                isGs1 = isGs1,
                symbologyIdentifier = if (isGs1) "]d2" else "]d1",
                contentType = if (isGs1) "GS1" else "TEXT",
                box = DetectionBox(points, Base64.encodeToString(bytes, Base64.NO_WRAP), fullWidth.toFloat() / fullHeight.coerceAtLeast(1)),
                source = "ml:$passName",
                frameId = frameId
            )
        }
        val distinct = decoded.distinctBy { Base64.encodeToString(it.rawBytes, Base64.NO_WRAP) }
        val run = SystemClock.elapsedRealtime() - started
        logger?.log(
            "ML_END",
            "frame" to frameId,
            "candidate" to candidateId,
            "pass" to passName,
            "run" to run,
            "age" to (SystemClock.elapsedRealtime() - frameStartedAt),
            "barcodes" to barcodes.size,
            "dataMatrix" to dataMatrixDetections,
            "payloads" to payloadDetections,
            "rawValueFallbacks" to rawValueFallbacks,
            "result" to distinct.size
        )
        return LiveGooglePass(distinct, regions, runMs = run)
    }

    private fun rememberBoostRois(
        source: Bitmap,
        bindings: List<CandidateEvidenceBinding>,
        frameId: Long,
        frameElapsedMs: Long
    ) {
        bindings.filter { it.eligibleForBoost && !it.alreadySubmitted }.forEach { binding ->
            val crop = centeredMlCrop(source, binding.region, CANDIDATE_CROP_PADDING) ?: return@forEach
            val geometryQuality = minOf(binding.region.width, binding.region.height) /
                maxOf(binding.region.width, binding.region.height).coerceAtLeast(1f)
            val quality = binding.evidence * 10f + geometryQuality * 2f - binding.centerJitter - binding.scaleDrift
            val roi = BoostRoi(
                identity = binding.identity, bitmap = crop.bitmap, frameId = frameId,
                frameElapsedMs = frameElapsedMs, offsetX = crop.originX.toFloat(), offsetY = crop.originY.toFloat(),
                fullWidth = source.width, fullHeight = source.height, quality = quality
            )
            synchronized(roiLock) {
                val ring = roiRing.getOrPut(binding.identity) { ArrayDeque() }
                ring.addLast(roi)
                while (ring.size > BOOST_ROI_RING_SIZE) {
                    ring.removeFirst().bitmap.takeIf { !it.isRecycled }?.recycle()
                }
            }
            logger?.log(
                "BOOST_ROI_BUFFER", "track" to binding.trackId, "generation" to binding.generation,
                "frame" to frameId, "quality" to quality, "state" to binding.state.name
            )
        }
    }

    suspend fun runBoost(
        cycleId: Long,
        eligible: List<CandidateEvidenceBinding>,
        experimentFrames: List<AnalyzerSnapshot>,
        timeoutMs: Long
    ): BoostRunResult {
        if (!boostRunning.compareAndSet(false, true)) {
            experimentFrames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            logger?.log("BOOST_SKIP", "cycle" to cycleId, "reason" to "worker_busy")
            return BoostRunResult(0, 0, 0, eligible.size, "worker_busy")
        }
        val done = CompletableDeferred<BoostRunResult>()
        try {
            boostExecutor.execute {
                var submitted = 0
                var completed = 0
                var decodedCount = 0
                var skipped = 0
                try {
                    eligible.distinctBy(CandidateEvidenceBinding::identity).forEach { binding ->
                        val identity = binding.identity
                        val selected = synchronized(roiLock) {
                            roiRing[identity]?.maxWithOrNull(compareBy<BoostRoi> { it.quality }.thenBy { it.frameId })
                        }
                        if (selected == null) {
                            skipped++
                            logger?.log("BOOST_SKIP", "cycle" to cycleId, "track" to identity.trackId, "generation" to identity.generation, "reason" to "no_roi")
                            return@forEach
                        }
                        val event = evidenceTracker.markSubmitted(identity)
                        if (event?.type != "SUBMITTED") {
                            skipped++
                            logger?.log(
                                "EVIDENCE", "event" to (event?.type ?: "NOT_ELIGIBLE"),
                                "track" to identity.trackId, "generation" to identity.generation,
                                "cycle" to cycleId
                            )
                            return@forEach
                        }
                        submitted++
                        logger?.log(
                            "EVIDENCE", "event" to event.type, "track" to identity.trackId,
                            "generation" to identity.generation, "cycle" to cycleId
                        )
                        logger?.log(
                            "BOOST_SELECT_ROI", "cycle" to cycleId, "track" to identity.trackId,
                            "generation" to identity.generation, "frame" to selected.frameId,
                            "quality" to selected.quality, "ring" to synchronized(roiLock) { roiRing[identity]?.size ?: 0 }
                        )
                        var successfulPass: LiveGooglePass? = null
                        for (kind in boostVariantPlan()) {
                            val preprocessingStarted = SystemClock.elapsedRealtimeNanos()
                            val variant = runCatching { ImageVariantFactory.create(selected.bitmap, kind) }.getOrNull() ?: continue
                            val preprocessMs = (SystemClock.elapsedRealtimeNanos() - preprocessingStarted) / 1_000_000.0
                            val pass = try {
                                decodeWithGoogle(
                                    scanner = boostScanner, bitmap = variant.bitmap,
                                    offsetX = selected.offsetX, offsetY = selected.offsetY,
                                    fullWidth = selected.fullWidth, fullHeight = selected.fullHeight,
                                    frameId = selected.frameId, frameStartedAt = selected.frameElapsedMs,
                                    candidateId = "boost:${identity.trackId}:${identity.generation}",
                                    passName = "boost:${kind.name.lowercase()}"
                                ).copy(preprocessMs = preprocessMs)
                            } finally {
                                if (variant.owned && !variant.bitmap.isRecycled) variant.bitmap.recycle()
                            }
                            statistics?.recordVariantPortfolio(
                                VariantPortfolioEvent(kind.name, pass.decoded.isNotEmpty(), true, true, preprocessMs, pass.runMs.toDouble())
                            )
                            logger?.log(
                                "BOOST_VARIANT", "cycle" to cycleId, "track" to identity.trackId,
                                "generation" to identity.generation, "variant" to kind.name,
                                "decoded" to pass.decoded.size, "preprocessMs" to preprocessMs, "runMs" to pass.runMs
                            )
                            if (pass.decoded.isNotEmpty()) {
                                successfulPass = pass
                                break
                            }
                        }
                        successfulPass?.let { pass ->
                            val captured = selected.bitmap.toLiveCapturedFrame()
                            onDecoded(pass.decoded.map { item -> item.copy(capturedFrame = captured) })
                            decodedCount += pass.decoded.size
                        }
                        completed++
                        evidenceTracker.markCompleted(identity, successfulPass != null)?.let { complete ->
                            logger?.log(
                                "EVIDENCE", "event" to complete.type, "track" to complete.trackId,
                                "generation" to complete.generation, "detail" to complete.detail, "cycle" to cycleId
                            )
                        }
                    }
                    if (experimentFrames.isNotEmpty()) runLoggingMatrixExperiment(cycleId, experimentFrames)
                    done.complete(
                        BoostRunResult(
                            submitted, completed, decodedCount, skipped,
                            if (submitted == 0) "no_eligible_identity" else "completed"
                        )
                    )
                } catch (t: Throwable) {
                    logger?.error("boost_worker", "cycle" to cycleId, "error" to t.javaClass.simpleName)
                    done.complete(BoostRunResult(submitted, completed, decodedCount, skipped, "error:${t.javaClass.simpleName}"))
                } finally {
                    experimentFrames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                    boostRunning.set(false)
                }
            }
        } catch (t: Throwable) {
            boostRunning.set(false)
            experimentFrames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            return BoostRunResult(0, 0, 0, eligible.size, "queue_rejected")
        }
        return withTimeoutOrNull(timeoutMs) { done.await() }
            ?: BoostRunResult(0, 0, 0, eligible.size, "caller_timeout")
    }

    /** Debug-only correlated matrix; results never reach UI, evidence, dedupe or statistics. */
    private fun runLoggingMatrixExperiment(cycleId: Long, snapshots: List<AnalyzerSnapshot>) {
        snapshots.take(3).forEachIndexed { frameIndex, snapshot ->
            LOGGING_MATRIX_VARIANTS.forEach { kind ->
                val variant = runCatching { ImageVariantFactory.create(snapshot.bitmap, kind) }.getOrNull() ?: return@forEach
                val started = SystemClock.elapsedRealtime()
                val results = runCatching { boostHardReader.read(variant.bitmap) }.getOrDefault(emptyList())
                val decoded = results.count { it.bytes != null && it.error == null }
                val proposed = results.count { it.bytes == null || it.error != null }
                logger?.log(
                    "BOOST_MATRIX", "cycle" to cycleId, "frameIndex" to frameIndex,
                    "frame" to snapshot.frameId, "variant" to kind.name,
                    "proposals" to proposed, "decoded" to decoded,
                    "runMs" to (SystemClock.elapsedRealtime() - started), "published" to 0
                )
                if (variant.owned && !variant.bitmap.isRecycled) variant.bitmap.recycle()
            }
        }
    }

    private fun publishDiagnostics(frameId: Long, frameStartedAt: Long) {
        val age = if (frameStartedAt > 0L) (SystemClock.elapsedRealtime() - frameStartedAt).coerceAtLeast(0L) else 0L
        onDiagnostics(pendingJobs + if (latest.get() != null) 1 else 0, inFlight.get(), age, frameId)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        latest.getAndSet(null)?.source?.recycle()
        fullScanner.close()
        workers.forEach { it.scanner.close(); it.executor.shutdownNow() }
        boostScanner.close()
        boostExecutor.shutdownNow()
        synchronized(roiLock) {
            roiRing.values.flatten().forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            roiRing.clear()
        }
        coordinator.shutdownNow()
    }

    private fun newScanner(): BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX)
            .enableAllPotentialBarcodes()
            .build()
    )
}

private const val ML_TIMEOUT_MS = 1_200L
private const val BOOST_ROI_RING_SIZE = 3
private const val BOOST_IDENTITY_LIMIT = 16
internal fun boostVariantPlan(): List<VariantKind> =
    listOf(VariantKind.ORIGINAL, VariantKind.CONTRAST_135, VariantKind.CLAHE)
private val LOGGING_MATRIX_VARIANTS = listOf(
    VariantKind.ORIGINAL, VariantKind.CONTRAST_135, VariantKind.CONTRAST_180,
    VariantKind.GAMMA_075, VariantKind.GAMMA_135, VariantKind.CLAHE,
    VariantKind.RED_CHANNEL, VariantKind.GREEN_CHANNEL
)

private data class LiveCandidateBatch(
    val source: Bitmap,
    val seedRegions: List<RecoveryRegion>,
    val evidenceBindings: List<CandidateEvidenceBinding>,
    val frameId: Long,
    val frameStartedAt: Long
)

private data class LiveGooglePass(
    val decoded: List<DecodedDataMatrix>,
    val regions: List<RecoveryRegion>,
    val runMs: Long = 0L,
    val preprocessMs: Double? = null
)

private fun List<Point>.toLiveRegion(offsetX: Float, offsetY: Float): RecoveryRegion? {
    if (size < 4) return null
    return RecoveryRegion(
        left = offsetX + minOf { it.x }.toFloat(),
        top = offsetY + minOf { it.y }.toFloat(),
        right = offsetX + maxOf { it.x }.toFloat(),
        bottom = offsetY + maxOf { it.y }.toFloat(),
        corners = take(4).map { PixelPoint(offsetX + it.x, offsetY + it.y) }
    )
}

private data class CenteredMlCrop(
    val bitmap: Bitmap,
    val originX: Int,
    val originY: Int,
    val centerErrorPx: Float,
    val edgePadded: Boolean
)

/**
 * Returns a square ML input whose geometric centre stays on [region]'s centre even when the
 * requested +padding square crosses a frame edge. The out-of-frame quiet area is synthetic;
 * source pixels themselves are never shifted. [originX]/[originY] may therefore be negative and
 * are intentionally used to map ML coordinates back to the oriented analysis frame.
 */
private fun centeredMlCrop(source: Bitmap, region: RecoveryRegion, padding: Float): CenteredMlCrop? = runCatching {
    val geometry = region.centeredCropGeometry(source.width, source.height, padding) ?: return@runCatching null
    val originX = geometry.originX
    val originY = geometry.originY
    val desiredSide = geometry.side
    val srcLeft = geometry.sourceLeft
    val srcTop = geometry.sourceTop
    val srcRight = geometry.sourceRight
    val srcBottom = geometry.sourceBottom

    val output = Bitmap.createBitmap(desiredSide, desiredSide, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawColor(Color.WHITE)
    val dstLeft = (srcLeft - originX).toFloat()
    val dstTop = (srcTop - originY).toFloat()
    canvas.drawBitmap(
        source,
        Rect(srcLeft, srcTop, srcRight, srcBottom),
        RectF(dstLeft, dstTop, dstLeft + (srcRight - srcLeft), dstTop + (srcBottom - srcTop)),
        Paint(Paint.FILTER_BITMAP_FLAG)
    )
    val localCenterX = region.centerX - originX
    val localCenterY = region.centerY - originY
    val outputCenter = desiredSide / 2f
    CenteredMlCrop(
        bitmap = output,
        originX = originX,
        originY = originY,
        centerErrorPx = kotlin.math.hypot(localCenterX - outputCenter, localCenterY - outputCenter),
        edgePadded = geometry.edgePadded
    )
}.getOrNull()

private fun cropLiveBitmap(source: Bitmap, region: RecoveryRegion): Bitmap? = runCatching {
    val left = region.left.toInt().coerceIn(0, source.width - 1)
    val top = region.top.toInt().coerceIn(0, source.height - 1)
    val right = region.right.toInt().coerceIn(left + 1, source.width)
    val bottom = region.bottom.toInt().coerceIn(top + 1, source.height)
    Bitmap.createBitmap(source, left, top, right - left, bottom - top)
}.getOrNull()

private fun RecoveryRegion.compact(): String = "${left.toInt()},${top.toInt()},${right.toInt()},${bottom.toInt()}"

private fun overlapRatio(first: RecoveryRegion, second: RecoveryRegion): Float {
    val left = maxOf(first.left, second.left)
    val top = maxOf(first.top, second.top)
    val right = minOf(first.right, second.right)
    val bottom = minOf(first.bottom, second.bottom)
    val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
    val smaller = minOf(first.area, second.area).coerceAtLeast(1f)
    return intersection / smaller
}

private fun Bitmap.toLiveCapturedFrame(): CapturedFrame? = try {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, 90, stream)
    val jpeg = stream.toByteArray()
    val hash = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    CapturedFrame(jpeg, width, height, hash)
} catch (_: Throwable) { null }
