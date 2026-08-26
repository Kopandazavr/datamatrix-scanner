package com.kopandazavr.datamatrixscanner.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Point
import android.os.SystemClock
import android.util.Base64
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.kopandazavr.datamatrixscanner.PipelineDebugLogger
import com.kopandazavr.datamatrixscanner.FocusLensMetadataMonitor
import com.kopandazavr.datamatrixscanner.PerformanceStageEvent
import com.kopandazavr.datamatrixscanner.ScannerStatisticsStore
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import zxingcpp.BarcodeReader

data class NormalizedPoint(val x: Float, val y: Float)

enum class DetectionHighlight { POTENTIAL, ACTIVE, DUPLICATE }

data class DetectionBox(
    val points: List<NormalizedPoint>,
    val key: String,
    val imageAspect: Float,
    val highlight: DetectionHighlight = DetectionHighlight.ACTIVE,
    val stableCandidate: Boolean = false,
    val trackId: Int? = null,
    val overlayAlpha: Float = 1f
)

data class CapturedFrame(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val sha256: String
)

data class DecodedDataMatrix(
    val rawBytes: ByteArray,
    val text: String?,
    val isGs1: Boolean,
    val symbologyIdentifier: String?,
    val contentType: String,
    val box: DetectionBox,
    val capturedFrame: CapturedFrame? = null,
    val source: String = "unknown",
    val frameId: Long = 0L,
    val actualFocusDistance: Float? = null,
    val targetSharpness: Float? = null,
    val focusStationary: Boolean? = null,
    val focusTriggered: Boolean = false
)

data class AnalyzerSnapshot(
    val bitmap: Bitmap,
    val frameId: Long,
    val frameElapsedMs: Long,
    val sensorTimestampNs: Long = 0L
)

data class CenterFocusDecision(
    val shouldRunAf: Boolean,
    val sharpness: Float?,
    val threshold: Float
)

data class CenterSharpnessSnapshot(
    val score: Float,
    val core: Float,
    val context: Float,
    val elapsedMs: Long,
    val sensorTimestampNs: Long = 0L,
    val analyzerFrameId: Long = 0L
)

internal data class NativeAfSignal(
    val candidateOpportunity: Long,
    val centralCandidateSignature: String?,
    val candidateElapsedMs: Long,
    val centerChangeGeneration: Long,
    val centerChangeElapsedMs: Long,
    val sharpness: Float?,
    val centerSharp: Boolean
)

internal class DataMatrixAnalyzer(
    private val onDecoded: (List<DecodedDataMatrix>) -> Unit,
    private val onPotentialBoxes: (List<DetectionBox>) -> Unit,
    private val logger: PipelineDebugLogger? = null,
    private val statistics: ScannerStatisticsStore? = null,
    private val focusMetadata: FocusLensMetadataMonitor? = null
) : ImageAnalysis.Analyzer, AutoCloseable {
    @Volatile var fullScreen: Boolean = false
    @Volatile var active: Boolean = true
    @Volatile var benchmarkPaused: Boolean = false
    @Volatile var enhancementMode: ScanEnhancementMode = ScanEnhancementMode.BALANCED

    private var lastAnalysisAt = 0L
    private var lastSharpnessAt = 0L
    @Volatile private var smoothedCenterSharpness = 0f
    @Volatile private var bestCenterSharpness = 0f
    @Volatile private var centerSharp = false
    @Volatile private var hasCenterSharpnessSample = false
    @Volatile private var latestCenterSharpness = 0f
    @Volatile private var latestCenterSharpnessCore = 0f
    @Volatile private var latestCenterSharpnessContext = 0f
    @Volatile private var latestCenterSharpnessElapsedMs = 0L
    @Volatile private var latestCenterSharpnessSensorTimestampNs = 0L
    @Volatile private var latestCenterSharpnessFrameId = 0L
    @Volatile private var focusSamplingActive = false
    @Volatile private var focusStrictCandidateSampling = false
    @Volatile private var latestFocusCandidateFrame: FocusCandidateFrameSnapshot? = null
    @Volatile private var motionReference: ByteArray? = null
    @Volatile private var motionRefocusNeeded = false
    @Volatile private var focusFailureRefocusNeeded = false
    @Volatile private var explicitRefocusNeeded = true
    @Volatile private var ignoreMotionUntil = 0L
    @Volatile private var candidateOpportunity = 0L
    @Volatile private var latestCentralCandidateSignature: String? = null
    @Volatile private var latestCandidateElapsedMs = 0L
    @Volatile private var centerChangeGeneration = 0L
    @Volatile private var centerChangeElapsedMs = 0L

    private var frameNumber = 0L
    private var incomingFramesThisSecond = 0
    private var processedFramesThisSecond = 0
    private var perfWindowStarted = SystemClock.elapsedRealtime()

    @Volatile private var fastJobs = 0
    @Volatile private var mlPending = 0
    @Volatile private var mlInFlight = 0
    @Volatile private var heavyPending = 0
    @Volatile private var heavyInFlight = 0
    @Volatile private var fastFrameAge = 0L
    @Volatile private var mlFrameAge = 0L
    @Volatile private var heavyFrameAge = 0L
    @Volatile private var diagnosticFrameId = 0L

    private val _diagnostics = MutableStateFlow(PipelineDiagnostics())
    val diagnostics: StateFlow<PipelineDiagnostics> = _diagnostics.asStateFlow()

    private val evidenceTracker = CandidateEvidenceTracker()

    private val liveCandidateProcessor = LiveCandidateProcessor(
        onDecoded = { onDecoded(enrichWithLatestFocus(it)) },
        onPotentialBoxes = { },
        logger = logger,
        statistics = statistics,
        evidenceTracker = evidenceTracker,
        onDiagnostics = { pending, inFlight, age, frameId ->
            mlPending = pending
            mlInFlight = inFlight
            mlFrameAge = age
            if (frameId > 0L) diagnosticFrameId = frameId
            publishDiagnostics()
        }
    )

    private val snapshotRequests = ConcurrentLinkedQueue<CompletableDeferred<AnalyzerSnapshot?>>()
    private val capturedKeys = object : LinkedHashMap<String, Unit>(512, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > 512
    }

    private val fastReader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.DATA_MATRIX),
            tryHarder = false,
            tryRotate = false,
            tryInvert = false,
            tryDownscale = false,
            maxNumberOfSymbols = 16,
            textMode = BarcodeReader.TextMode.PLAIN
        )
    )

    private val hardReader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.DATA_MATRIX),
            tryHarder = true,
            tryRotate = true,
            tryInvert = true,
            tryDownscale = true,
            tryDenoise = true,
            returnErrors = true,
            maxNumberOfSymbols = 32,
            textMode = BarcodeReader.TextMode.PLAIN
        )
    )

    suspend fun awaitSnapshot(timeoutMs: Long = 1_500L): AnalyzerSnapshot? {
        val deferred = CompletableDeferred<AnalyzerSnapshot?>()
        snapshotRequests.add(deferred)
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            snapshotRequests.remove(deferred)
        }
    }

    internal suspend fun runBoost(cycleId: Long, timeoutMs: Long = 20_000L): BoostRunResult {
        logger?.log("BOOST_START", "cycle" to cycleId, "focusCommands" to 0)
        heavyPending = 1
        publishDiagnostics()
        val experimentFrames = if (logger?.isRecording == true) {
            buildList {
                awaitSnapshot(1_200L)?.let(::add)
                delay(140L)
                awaitSnapshot(1_200L)?.let(::add)
            }
        } else emptyList()
        // Keep the post-tap observation window bounded. Evidence and ROI samples collected before
        // the tap remain in the same tracker/ring and are intentionally eligible.
        delay(BOOST_POST_TAP_EVIDENCE_MS)
        val eligible = evidenceTracker.eligibleBindings(
            liveCandidateProcessor.latestFrameWidth,
            liveCandidateProcessor.latestFrameHeight
        )
        logger?.log("BOOST_EVIDENCE_WINDOW", "cycle" to cycleId, "eligible" to eligible.size, "snapshots" to experimentFrames.size)
        heavyPending = 0
        heavyInFlight = 1
        publishDiagnostics()
        return try {
            liveCandidateProcessor.runBoost(cycleId, eligible, experimentFrames, timeoutMs).also { result ->
                logger?.log(
                    "BOOST_END", "cycle" to cycleId, "submitted" to result.submitted,
                    "completed" to result.completed, "decoded" to result.decoded,
                    "skipped" to result.skipped, "focusCommands" to 0, "reason" to result.reason
                )
            }
        } finally {
            heavyPending = 0
            heavyInFlight = 0
            publishDiagnostics()
        }
    }

    /** Independent of recognition and candidate boxes; read by the focus controller. */
    fun needsCenterRefocus(): Boolean =
        !centerSharp || motionRefocusNeeded || focusFailureRefocusNeeded || explicitRefocusNeeded

    fun manualFocusDecision(): CenterFocusDecision {
        val score = smoothedCenterSharpness.takeIf { hasCenterSharpnessSample }
        val threshold = manualFocusThreshold(bestCenterSharpness)
        return CenterFocusDecision(
            shouldRunAf = needsManualAf(score, bestCenterSharpness, motionRefocusNeeded, focusFailureRefocusNeeded),
            sharpness = score,
            threshold = threshold
        )
    }

    fun currentCenterSharpness(): Float? = smoothedCenterSharpness.takeIf { hasCenterSharpnessSample }

    fun nativeAfSignal(): NativeAfSignal = NativeAfSignal(
        candidateOpportunity = candidateOpportunity,
        centralCandidateSignature = latestCentralCandidateSignature,
        candidateElapsedMs = latestCandidateElapsedMs,
        centerChangeGeneration = centerChangeGeneration,
        centerChangeElapsedMs = centerChangeElapsedMs,
        sharpness = currentCenterSharpness(),
        centerSharp = centerSharp
    )

    fun currentCenterSharpnessSnapshot(): CenterSharpnessSnapshot? =
        if (latestCenterSharpnessElapsedMs > 0L) {
            CenterSharpnessSnapshot(
                score = latestCenterSharpness,
                core = latestCenterSharpnessCore,
                context = latestCenterSharpnessContext,
                elapsedMs = latestCenterSharpnessElapsedMs,
                sensorTimestampNs = latestCenterSharpnessSensorTimestampNs,
                analyzerFrameId = latestCenterSharpnessFrameId
            )
        } else {
            null
        }

    fun setFocusSamplingActive(active: Boolean) {
        focusSamplingActive = active
        if (!active) focusStrictCandidateSampling = false
        latestFocusCandidateFrame = null
        if (active) lastSharpnessAt = 0L
    }

    /**
     * Probe mode keeps the expensive tryHarder candidate pass sparse. Strict mode is enabled
     * only for the short precheck/final confirmation windows where consecutive geometry matters.
     */
    fun setFocusCandidateSamplingStrict(strict: Boolean) {
        focusStrictCandidateSampling = focusSamplingActive && strict
        latestFocusCandidateFrame = null
    }

    fun currentFocusCandidateFrameSnapshot(): FocusCandidateFrameSnapshot? = latestFocusCandidateFrame

    fun requestCenterRefocus() {
        explicitRefocusNeeded = true
    }

    fun onCenterFocusStarted() {
        motionRefocusNeeded = false
        focusFailureRefocusNeeded = false
        explicitRefocusNeeded = false
        motionReference = null
        ignoreMotionUntil = System.currentTimeMillis() + 750L
    }

    fun onCenterFocusCompleted(success: Boolean) {
        focusFailureRefocusNeeded = !success
        motionReference = null
        ignoreMotionUntil = System.currentTimeMillis() + 350L
    }

    override fun analyze(image: ImageProxy) {
        if (!active || benchmarkPaused) {
            image.close()
            return
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val frameId = ++frameNumber
        incomingFramesThisSecond++
        diagnosticFrameId = frameId
        val frameElapsed = imageTimestampElapsedMs(image, nowElapsed)
        val initialAge = (nowElapsed - frameElapsed).coerceAtLeast(0L)
        logger?.log(
            "FRAME_RECEIVED",
            "frame" to frameId,
            "age" to initialAge,
            "size" to "${image.width}x${image.height}",
            "rotation" to image.imageInfo.rotationDegrees,
            "mode" to if (fullScreen) "fullscreen" else "preview"
        )

        // During manual focus no ML crop jobs are launched. Let cheap centre-sharpness probes see
        // essentially every camera frame instead of throttling the loop to 20 fps.
        val interval = when {
            focusSamplingActive -> 28L
            fullScreen -> 25L
            else -> 50L
        }
        if (nowElapsed - lastAnalysisAt < interval) {
            logger?.log("FRAME_SKIP", "frame" to frameId, "reason" to "throttle", "age" to initialAge)
            maybePerfHeartbeat(nowElapsed)
            image.close()
            return
        }
        lastAnalysisAt = nowElapsed
        processedFramesThisSecond++
        fastJobs = 1
        fastFrameAge = initialAge
        publishDiagnostics()
        logger?.log("FRAME_PROCESS", "frame" to frameId, "age" to initialAge, "queue" to diagnostics.value.totalJobs)

        try {
            val rotation = image.imageInfo.rotationDegrees
            val crop = image.cropRect
            val sharpnessIntervalMs = if (focusSamplingActive) 0L else 250L
            if (nowWall - lastSharpnessAt >= sharpnessIntervalMs) {
                lastSharpnessAt = nowWall
                image.planes.firstOrNull()?.let { plane ->
                    sampleCenterLuma(
                        luma = plane.buffer,
                        imageWidth = image.width,
                        imageHeight = image.height,
                        rowStride = plane.rowStride,
                        pixelStride = plane.pixelStride,
                        cropLeft = crop.left,
                        cropTop = crop.top,
                        cropRight = crop.right,
                        cropBottom = crop.bottom
                    )?.let { sample ->
                        if (nowWall < ignoreMotionUntil) {
                            motionReference = sample
                        } else {
                            val reference = motionReference
                            if (reference != null && estimateCenterChange(reference, sample)?.let { it >= CENTER_CHANGE_THRESHOLD } == true) {
                                motionRefocusNeeded = true
                                centerChangeGeneration++
                                centerChangeElapsedMs = nowElapsed
                            } else if (reference == null) {
                                motionReference = sample
                            }
                        }
                    }
                    estimateTargetSharpness(
                        luma = plane.buffer,
                        imageWidth = image.width,
                        imageHeight = image.height,
                        rowStride = plane.rowStride,
                        pixelStride = plane.pixelStride,
                        cropLeft = crop.left,
                        cropTop = crop.top,
                        cropRight = crop.right,
                        cropBottom = crop.bottom
                    )?.let { profile ->
                        val score = profile.score
                        latestCenterSharpness = score
                        latestCenterSharpnessCore = profile.core
                        latestCenterSharpnessContext = profile.context
                        // Focus freshness must follow the sensor/frame timestamp, not the time
                        // this analyzer eventually reached the sharpness code. A slow tryHarder
                        // pass can otherwise make an old lens position look like a fresh probe.
                        latestCenterSharpnessElapsedMs = frameElapsed
                        latestCenterSharpnessSensorTimestampNs = image.imageInfo.timestamp
                        latestCenterSharpnessFrameId = frameId
                        smoothedCenterSharpness = if (hasCenterSharpnessSample) smoothedCenterSharpness * .45f + score * .55f else score
                        hasCenterSharpnessSample = true
                        bestCenterSharpness = maxOf(smoothedCenterSharpness, bestCenterSharpness * .995f)
                        centerSharp = updateCenterSharpState(
                            wasSharp = centerSharp,
                            score = smoothedCenterSharpness,
                            sharpThreshold = maxOf(CENTER_SHARP_THRESHOLD, bestCenterSharpness * .78f),
                            blurThreshold = maxOf(CENTER_BLUR_THRESHOLD, bestCenterSharpness * .60f)
                        )
                    }
                }
            }

            // Fulfil a user-requested fixed frame before doing slower downstream work.
            val snapshotDeferred = snapshotRequests.poll()
            if (snapshotDeferred != null && !snapshotDeferred.isCompleted) {
                val bitmap = captureVisibleBitmap(image)
                snapshotDeferred.complete(bitmap?.let { AnalyzerSnapshot(it, frameId, frameElapsed, image.imageInfo.timestamp) })
                logger?.log("SNAPSHOT", "frame" to frameId, "ok" to if (bitmap != null) 1 else 0, "age" to (SystemClock.elapsedRealtime() - frameElapsed))
            }

            val outputWidth = if (rotation == 90 || rotation == 270) crop.height() else crop.width()
            val outputHeight = if (rotation == 90 || rotation == 270) crop.width() else crop.height()

            val fastStarted = SystemClock.elapsedRealtime()
            val fastResults = fastReader.read(image)
            val fastRun = SystemClock.elapsedRealtime() - fastStarted

            // The expensive tryHarder pass used to run on every focus frame and became the main
            // pacing bottleneck between lens steps. Fast probe mode keeps it sparse; only the
            // short strict precheck/final-confirm windows request it on every analyzed frame.
            val hardEvery = when {
                focusSamplingActive && focusStrictCandidateSampling -> 1L
                focusSamplingActive -> 3L
                else -> 3L
            }
            val ranHardPass = frameId % hardEvery == 0L
            val hardStarted = SystemClock.elapsedRealtime()
            val hardResults = if (ranHardPass) hardReader.read(image) else emptyList()
            val hardRun = if (ranHardPass) SystemClock.elapsedRealtime() - hardStarted else 0L

            val potentialRegions = if (ranHardPass) {
                mergeRecoveryRegions(
                    hardResults.filter { it.error != null || it.bytes == null }.mapNotNull { result ->
                        listOf(result.position.topLeft, result.position.topRight, result.position.bottomRight, result.position.bottomLeft).toRecoveryRegion()
                    },
                    outputWidth,
                    outputHeight,
                    maxRegions = 12
                )
            } else emptyList()

            if (ranHardPass) {
                candidateOpportunity++
                val hasCandidateEvidence = potentialRegions.isNotEmpty() ||
                    fastResults.any { it.bytes != null && it.error == null } ||
                    hardResults.any { it.bytes != null && it.error == null }
                statistics?.recordCandidateEvidence(hasCandidateEvidence, frameElapsed)
            }

            logger?.log(
                "ZX_END",
                "frame" to frameId,
                "run" to (fastRun + hardRun),
                "fastRun" to fastRun,
                "hardRun" to hardRun,
                "candidates" to potentialRegions.size,
                "fastDecoded" to fastResults.count { it.bytes != null && it.error == null },
                "hardDecoded" to hardResults.count { it.bytes != null && it.error == null }
            )
            statistics?.recordPerformanceStage(
                PerformanceStageEvent(
                    preprocessMs = null,
                    decoderMs = (fastRun + hardRun).toDouble(),
                    endToEndMs = (SystemClock.elapsedRealtime() - frameElapsed).coerceAtLeast(0L).toDouble()
                )
            )

            if (focusSamplingActive) {
                val focusObservations = buildList {
                    if (ranHardPass) {
                        hardResults.forEach { result ->
                            result.toFocusCandidateObservation(outputWidth, outputHeight)?.let(::add)
                        }
                    }
                    fastResults.forEach { result ->
                        if (result.bytes != null && result.error == null) {
                            result.toFocusCandidateObservation(outputWidth, outputHeight)?.let(::add)
                        }
                    }
                }
                latestFocusCandidateFrame = FocusCandidateFrameSnapshot(
                    frameId = frameId,
                    capturedElapsedMs = frameElapsed,
                    sensorTimestampNs = image.imageInfo.timestamp,
                    observations = mergeFocusCandidateObservations(focusObservations)
                )
                logger?.log(
                    "FOCUS_CANDIDATE_FRAME",
                    "frame" to frameId,
                    "zones" to latestFocusCandidateFrame?.observations?.size,
                    "decoded" to latestFocusCandidateFrame?.observations?.count { it.decoded },
                    "hard" to if (ranHardPass) 1 else 0
                )
            }

            if (ranHardPass) {
                val rawBoxes = potentialRegions.mapIndexed { index, region ->
                    region.toPotentialDetectionBox(outputWidth, outputHeight, "live:$frameId:$index")
                }
                val evidence = evidenceTracker.update(
                    regions = potentialRegions,
                    width = outputWidth,
                    height = outputHeight,
                    opportunity = candidateOpportunity,
                    elapsedMs = frameElapsed
                )
                evidence.events.forEach { event ->
                    logger?.log(
                        "EVIDENCE", "event" to event.type, "track" to event.trackId,
                        "generation" to event.generation, "detail" to event.detail
                    )
                    when (event.type) {
                        "ELIGIBLE" -> evidence.stableBoxes.firstOrNull { it.trackId == event.trackId }?.let { statistics?.recordCandidate(it) }
                        "LOST" -> event.detail.substringAfter("lifetime=", "").substringBefore(',').toIntOrNull()
                            ?.let { statistics?.recordCandidateLifetime(it) }
                    }
                }
                val central = evidence.bindings.filter { binding ->
                    val cx = binding.region.centerX / outputWidth.coerceAtLeast(1)
                    val cy = binding.region.centerY / outputHeight.coerceAtLeast(1)
                    binding.eligibleForBoost && cx in .32f..68f && cy in .32f..68f
                }.maxByOrNull { it.evidence }
                if (central != null) {
                    latestCentralCandidateSignature = "${central.trackId}:${central.generation}"
                    latestCandidateElapsedMs = frameElapsed
                }
                onPotentialBoxes(rawBoxes + evidence.stableBoxes)
                potentialRegions.forEachIndexed { index, region ->
                    logger?.log(
                        "CANDIDATE",
                        "frame" to frameId,
                        "candidate" to "c$index",
                        "roi" to "${region.left.toInt()},${region.top.toInt()},${region.right.toInt()},${region.bottom.toInt()}"
                    )
                }
                // Candidate geometry itself is the focus signal. Do not start extra ML crop
                // work during the sweep: it is unnecessary for choosing the lens distance and
                // would only make the three-frame focus windows slower.
                if (potentialRegions.isNotEmpty() && !focusSamplingActive) {
                    val captureStarted = SystemClock.elapsedRealtimeNanos()
                    captureVisibleBitmap(image)?.let { snapshot ->
                        logger?.log("CROP_FRAME", "frame" to frameId, "run" to ((SystemClock.elapsedRealtimeNanos() - captureStarted) / 1_000_000.0))
                        liveCandidateProcessor.submit(snapshot, potentialRegions, evidence.bindings, frameId, frameElapsed)
                    }
                }
            }

            val decoded = buildList {
                addAll(decodeZxingResults(fastResults, outputWidth, outputHeight, "zxing_fast", frameId, image.imageInfo.timestamp))
                addAll(decodeZxingResults(hardResults, outputWidth, outputHeight, "zxing_hard", frameId, image.imageInfo.timestamp))
            }.distinctBy { Base64.encodeToString(it.rawBytes, Base64.NO_WRAP) }

            if (decoded.isNotEmpty()) {
                val uncapturedKeys = decoded.mapNotNull { item ->
                    Base64.encodeToString(item.rawBytes, Base64.NO_WRAP).takeIf { it !in capturedKeys }
                }.toSet()
                val frame = if (uncapturedKeys.isNotEmpty()) captureVisibleFrame(image) else null
                uncapturedKeys.forEach { capturedKeys[it] = Unit }
                onDecoded(decoded.map { item ->
                    val key = Base64.encodeToString(item.rawBytes, Base64.NO_WRAP)
                    if (frame != null && key in uncapturedKeys) item.copy(capturedFrame = frame) else item
                })
            }

        } catch (t: Throwable) {
            logger?.error("analyzer", "frame" to frameId, "error" to t.javaClass.simpleName)
        } finally {
            fastJobs = 0
            fastFrameAge = 0L
            publishDiagnostics()
            maybePerfHeartbeat(SystemClock.elapsedRealtime())
            image.close()
        }
    }

    private fun decodeZxingResults(
        results: List<BarcodeReader.Result>,
        outputWidth: Int,
        outputHeight: Int,
        source: String,
        frameId: Long,
        sensorTimestampNs: Long
    ): List<DecodedDataMatrix> = results.mapNotNull { result ->
        val bytes = result.bytes ?: return@mapNotNull null
        if (result.format != BarcodeReader.Format.DATA_MATRIX || result.error != null) return@mapNotNull null
        val points = listOf(result.position.topLeft, result.position.topRight, result.position.bottomRight, result.position.bottomLeft)
            .map { it.normalize(outputWidth, outputHeight) }
        val matchedFocus = focusMetadata?.closest(sensorTimestampNs)
        DecodedDataMatrix(
            rawBytes = bytes,
            text = result.text,
            isGs1 = result.contentType == BarcodeReader.ContentType.GS1,
            symbologyIdentifier = result.symbologyIdentifier,
            contentType = result.contentType.name,
            box = DetectionBox(points, Base64.encodeToString(bytes, Base64.NO_WRAP), outputWidth.toFloat() / outputHeight.coerceAtLeast(1)),
            source = source,
            frameId = frameId,
            actualFocusDistance = matchedFocus?.actualDistance,
            targetSharpness = latestCenterSharpness.takeIf {
                latestCenterSharpnessFrameId == frameId && latestCenterSharpnessSensorTimestampNs == sensorTimestampNs
            },
            focusStationary = matchedFocus?.stationary,
            focusTriggered = focusSamplingActive
        )
    }

    private fun enrichWithLatestFocus(items: List<DecodedDataMatrix>): List<DecodedDataMatrix> {
        val latest = focusMetadata?.latest()
        return items.map { item ->
            item.copy(
                actualFocusDistance = item.actualFocusDistance ?: latest?.actualDistance,
                targetSharpness = item.targetSharpness ?: currentCenterSharpness(),
                focusStationary = item.focusStationary ?: latest?.stationary,
                focusTriggered = item.focusTriggered || focusSamplingActive
            )
        }
    }

    private fun publishDiagnostics() {
        val oldest = maxOf(fastFrameAge, mlFrameAge, heavyFrameAge)
        _diagnostics.value = PipelineDiagnostics(
            fastJobs = fastJobs,
            mlPending = mlPending,
            mlInFlight = mlInFlight,
            heavyPending = heavyPending,
            heavyInFlight = heavyInFlight,
            oldestFrameAgeMs = oldest,
            currentFrameId = diagnosticFrameId,
            updatedAtElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    private fun maybePerfHeartbeat(now: Long) {
        if (now - perfWindowStarted < 1_000L) return
        val d = diagnostics.value
        logger?.log(
            "PERF",
            "incomingFPS" to incomingFramesThisSecond,
            "analyzedFPS" to processedFramesThisSecond,
            "fastQ" to d.fastJobs,
            "mlQueue" to d.mlPending,
            "mlInFlight" to d.mlInFlight,
            "heavyQueue" to d.heavyPending,
            "heavyInFlight" to d.heavyInFlight,
            "age" to d.oldestFrameAgeMs,
            "memoryMB" to ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024))
        )
        val focus = focusMetadata?.latest()
        logger?.log(
            "FOCUS_SCENE",
            "actualDistance" to focus?.actualDistance,
            "lensState" to FocusLensMetadataMonitor.lensStateName(focus?.lensState),
            "afMode" to FocusLensMetadataMonitor.afModeName(focus?.afMode),
            "afState" to FocusLensMetadataMonitor.afStateName(focus?.afState),
            "aeState" to focus?.aeState,
            "exposureNs" to focus?.exposureTimeNs,
            "iso" to focus?.iso,
            "physicalCamera" to focus?.physicalCameraId,
            "cropRegion" to focus?.cropRegion,
            "centerSharpness" to currentCenterSharpness(),
            "centerSharp" to if (centerSharp) 1 else 0,
            "sceneGeneration" to centerChangeGeneration,
            "candidateOpportunity" to candidateOpportunity
        )
        incomingFramesThisSecond = 0
        processedFramesThisSecond = 0
        perfWindowStarted = now
    }

    override fun close() {
        while (true) {
            val pending = snapshotRequests.poll() ?: break
            pending.complete(null)
        }
        liveCandidateProcessor.close()
    }

}

private const val BOOST_POST_TAP_EVIDENCE_MS = 520L

private fun BarcodeReader.Result.toFocusCandidateObservation(
    outputWidth: Int,
    outputHeight: Int
): FocusCandidateObservation? {
    if (format != BarcodeReader.Format.DATA_MATRIX) return null
    val points = listOf(position.topLeft, position.topRight, position.bottomRight, position.bottomLeft)
    if (points.size < 4) return null
    val width = outputWidth.coerceAtLeast(1).toFloat()
    val height = outputHeight.coerceAtLeast(1).toFloat()
    val left = points.minOf { it.x }.toFloat() / width
    val top = points.minOf { it.y }.toFloat() / height
    val right = points.maxOf { it.x }.toFloat() / width
    val bottom = points.maxOf { it.y }.toFloat() / height
    return FocusCandidateObservation(
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        right = right.coerceIn(0f, 1f),
        bottom = bottom.coerceIn(0f, 1f),
        decoded = bytes != null && error == null
    ).takeIf { it.width >= .004f && it.height >= .004f }
}

private fun imageTimestampElapsedMs(image: ImageProxy, nowElapsed: Long): Long {
    val candidate = image.imageInfo.timestamp / 1_000_000L
    return if (candidate in (nowElapsed - 60_000L)..(nowElapsed + 5_000L)) candidate else nowElapsed
}

private fun captureVisibleBitmap(image: ImageProxy): Bitmap? = try {
    val full = image.toBitmap()
    val crop = image.cropRect
    val left = crop.left.coerceIn(0, full.width - 1)
    val top = crop.top.coerceIn(0, full.height - 1)
    val width = crop.width().coerceAtMost(full.width - left).coerceAtLeast(1)
    val height = crop.height().coerceAtMost(full.height - top).coerceAtLeast(1)
    val cropped = Bitmap.createBitmap(full, left, top, width, height)
    if (cropped !== full) full.recycle()
    val rotation = image.imageInfo.rotationDegrees
    if (rotation == 0) cropped else Bitmap.createBitmap(
        cropped, 0, 0, cropped.width, cropped.height,
        Matrix().apply { postRotate(rotation.toFloat()) }, true
    ).also { if (it !== cropped) cropped.recycle() }
} catch (_: Throwable) { null }

private fun captureVisibleFrame(image: ImageProxy): CapturedFrame? = try {
    val oriented = captureVisibleBitmap(image) ?: return null
    val stream = ByteArrayOutputStream()
    oriented.compress(Bitmap.CompressFormat.JPEG, 90, stream)
    val jpeg = stream.toByteArray()
    val hash = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    CapturedFrame(jpeg, oriented.width, oriented.height, hash).also { oriented.recycle() }
} catch (_: Throwable) { null }

private fun Point.normalize(width: Int, height: Int) = NormalizedPoint(
    x = (x.toFloat() / width.coerceAtLeast(1)).coerceIn(0f, 1f),
    y = (y.toFloat() / height.coerceAtLeast(1)).coerceIn(0f, 1f)
)

private fun List<Point>.toRecoveryRegion(): RecoveryRegion? {
    if (size < 4) return null
    return RecoveryRegion(
        left = minOf { it.x }.toFloat(), top = minOf { it.y }.toFloat(),
        right = maxOf { it.x }.toFloat(), bottom = maxOf { it.y }.toFloat(),
        corners = take(4).map { PixelPoint(it.x.toFloat(), it.y.toFloat()) }
    )
}
