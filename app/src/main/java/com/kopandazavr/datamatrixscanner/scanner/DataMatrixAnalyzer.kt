package com.kopandazavr.datamatrixscanner.scanner

import android.graphics.Point
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import zxingcpp.BarcodeReader

data class NormalizedPoint(val x: Float, val y: Float)

enum class DetectionHighlight { POTENTIAL, ACTIVE, DUPLICATE }

data class DetectionBox(
    val points: List<NormalizedPoint>,
    val key: String,
    val imageAspect: Float,
    val highlight: DetectionHighlight = DetectionHighlight.ACTIVE
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
    val capturedFrame: CapturedFrame? = null
)

class DataMatrixAnalyzer(
    private val onDecoded: (List<DecodedDataMatrix>) -> Unit,
    private val onPotentialBoxes: (List<DetectionBox>) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {
    @Volatile var fullScreen: Boolean = false
    @Volatile var enhancementMode: ScanEnhancementMode = ScanEnhancementMode.BALANCED
    private var lastAnalysisAt = 0L
    private var lastSharpnessAt = 0L
    private var smoothedCenterSharpness = 0f
    private var bestCenterSharpness = 0f
    private var centerSharp = false
    private var hasCenterSharpnessSample = false
    @Volatile private var motionReference: ByteArray? = null
    @Volatile private var motionRefocusNeeded = false
    @Volatile private var focusFailureRefocusNeeded = false
    @Volatile private var ignoreMotionUntil = 0L
    private var frameNumber = 0L
    private val rescueProcessor = RescueDataMatrixProcessor(onDecoded, onPotentialBoxes)
    private val targetedCaptureRequested = AtomicBoolean(false)
    private val pendingTargetedFrame = AtomicReference<Bitmap?>(null)
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

    /** The next analyzed frame is preserved and receives the strongest rescue profile. */
    fun requestTargetedRescue() {
        targetedCaptureRequested.set(true)
    }

    /** Independent of recognition and candidate boxes; read by the focus loop. */
    fun needsCenterRefocus(): Boolean = !centerSharp || motionRefocusNeeded || focusFailureRefocusNeeded

    fun onCenterFocusStarted() {
        motionRefocusNeeded = false
        focusFailureRefocusNeeded = false
        motionReference = null
        ignoreMotionUntil = System.currentTimeMillis() + 750L
    }

    fun onCenterFocusCompleted(success: Boolean) {
        focusFailureRefocusNeeded = !success
        motionReference = null
        ignoreMotionUntil = System.currentTimeMillis() + 350L
    }

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        val interval = if (fullScreen) 25L else 50L
        if (now - lastAnalysisAt < interval) {
            image.close()
            return
        }
        lastAnalysisAt = now
        try {
            val rotation = image.imageInfo.rotationDegrees
            val crop = image.cropRect
            if (now - lastSharpnessAt >= 250L) {
                lastSharpnessAt = now
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
                        if (now < ignoreMotionUntil) {
                            motionReference = sample
                        } else {
                            val reference = motionReference
                            if (
                                reference != null &&
                                estimateCenterChange(reference, sample)?.let {
                                    it >= CENTER_CHANGE_THRESHOLD
                                } == true
                            ) {
                                motionRefocusNeeded = true
                            } else if (reference == null) {
                                motionReference = sample
                            }
                        }
                    }
                    estimateCenterSharpness(
                        luma = plane.buffer,
                        imageWidth = image.width,
                        imageHeight = image.height,
                        rowStride = plane.rowStride,
                        pixelStride = plane.pixelStride,
                        cropLeft = crop.left,
                        cropTop = crop.top,
                        cropRight = crop.right,
                        cropBottom = crop.bottom
                    )?.let { score ->
                        smoothedCenterSharpness = if (hasCenterSharpnessSample) {
                            smoothedCenterSharpness * .45f + score * .55f
                        } else {
                            score
                        }
                        hasCenterSharpnessSample = true
                        bestCenterSharpness = maxOf(
                            smoothedCenterSharpness,
                            bestCenterSharpness * .995f
                        )
                        centerSharp = updateCenterSharpState(
                            wasSharp = centerSharp,
                            score = smoothedCenterSharpness,
                            sharpThreshold = maxOf(
                                CENTER_SHARP_THRESHOLD,
                                bestCenterSharpness * .72f
                            ),
                            blurThreshold = maxOf(
                                CENTER_BLUR_THRESHOLD,
                                bestCenterSharpness * .55f
                            )
                        )
                    }
                }
            }
            val outputWidth = if (rotation == 90 || rotation == 270) crop.height() else crop.width()
            val outputHeight = if (rotation == 90 || rotation == 270) crop.width() else crop.height()
            frameNumber += 1
            val fastResults = fastReader.read(image)
            val hardEvery = 3L
            val ranHardPass = frameNumber % hardEvery == 0L && !rescueProcessor.isRunning
            val hardResults = if (ranHardPass) hardReader.read(image) else emptyList()
            val results = fastResults + hardResults
            if (ranHardPass) {
                val potentialRegions = mergeRecoveryRegions(
                    hardResults
                        .filter { it.error != null || it.bytes == null }
                        .mapNotNull { result ->
                            listOf(
                                result.position.topLeft,
                                result.position.topRight,
                                result.position.bottomRight,
                                result.position.bottomLeft
                            ).toRecoveryRegion()
                        },
                    outputWidth,
                    outputHeight,
                    maxRegions = 12
                )
                if (potentialRegions.isNotEmpty()) {
                    onPotentialBoxes(
                        potentialRegions.mapIndexed { index, region ->
                            region.toPotentialDetectionBox(outputWidth, outputHeight, "live:$index")
                        }
                    )
                }
            }
            val decoded = results.mapNotNull { result ->
                val bytes = result.bytes ?: return@mapNotNull null
                if (result.format != BarcodeReader.Format.DATA_MATRIX || result.error != null) return@mapNotNull null
                val points = listOf(
                    result.position.topLeft,
                    result.position.topRight,
                    result.position.bottomRight,
                    result.position.bottomLeft
                ).map { it.normalize(outputWidth, outputHeight) }
                DecodedDataMatrix(
                    rawBytes = bytes,
                    text = result.text,
                    isGs1 = result.contentType == BarcodeReader.ContentType.GS1,
                    symbologyIdentifier = result.symbologyIdentifier,
                    contentType = result.contentType.name,
                    box = DetectionBox(
                        points = points,
                        key = bytes.contentHashCode().toString(),
                        imageAspect = outputWidth.toFloat() / outputHeight.coerceAtLeast(1)
                    )
                )
            }.distinctBy { it.rawBytes.contentHashCode() }
            if (decoded.isEmpty()) {
                onDecoded(emptyList())
            } else {
                val uncapturedKeys = decoded.mapNotNull { item ->
                    Base64.encodeToString(item.rawBytes, Base64.NO_WRAP).takeIf { it !in capturedKeys }
                }.toSet()
                val frame = if (uncapturedKeys.isNotEmpty()) captureVisibleFrame(image) else null
                uncapturedKeys.forEach { capturedKeys[it] = Unit }
                onDecoded(decoded.map { item ->
                    val key = Base64.encodeToString(item.rawBytes, Base64.NO_WRAP)
                    if (frame != null && key in uncapturedKeys) {
                        item.copy(capturedFrame = frame)
                    } else item
                })
            }

            if (targetedCaptureRequested.compareAndSet(true, false)) {
                captureVisibleBitmap(image)?.let { snapshot ->
                    pendingTargetedFrame.getAndSet(snapshot)?.recycle()
                }
            }

            val targeted = pendingTargetedFrame.get()
            if (!rescueProcessor.isRunning && targeted != null && pendingTargetedFrame.compareAndSet(targeted, null)) {
                if (!rescueProcessor.start(targeted, ScanEnhancementMode.AGGRESSIVE)) {
                    targeted.recycle()
                }
            }

            val mode = enhancementMode
            if (pendingTargetedFrame.get() == null && RescueScanPolicy.shouldStart(rescueProcessor.isRunning, mode)) {
                captureVisibleBitmap(image)?.let { snapshot ->
                    if (!rescueProcessor.start(snapshot, mode)) snapshot.recycle()
                }
            }
        } catch (_: Throwable) {
            // A malformed frame must never stop the camera analyzer.
        } finally {
            image.close()
        }
    }

    override fun close() {
        pendingTargetedFrame.getAndSet(null)?.recycle()
        rescueProcessor.close()
    }
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
    val oriented = if (rotation == 0) cropped else Bitmap.createBitmap(
        cropped,
        0,
        0,
        cropped.width,
        cropped.height,
        Matrix().apply { postRotate(rotation.toFloat()) },
        true
    ).also { if (it !== cropped) cropped.recycle() }
    oriented
} catch (_: Throwable) {
    null
}

private fun captureVisibleFrame(image: ImageProxy): CapturedFrame? = try {
    val oriented = captureVisibleBitmap(image) ?: return null
    val stream = ByteArrayOutputStream()
    oriented.compress(Bitmap.CompressFormat.JPEG, 90, stream)
    val jpeg = stream.toByteArray()
    val hash = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    CapturedFrame(jpeg, oriented.width, oriented.height, hash).also { oriented.recycle() }
} catch (_: Throwable) {
    null
}

private fun Point.normalize(width: Int, height: Int) = NormalizedPoint(
    x = (x.toFloat() / width.coerceAtLeast(1)).coerceIn(0f, 1f),
    y = (y.toFloat() / height.coerceAtLeast(1)).coerceIn(0f, 1f)
)

private fun List<Point>.toRecoveryRegion(): RecoveryRegion? {
    if (size < 4) return null
    return RecoveryRegion(
        left = minOf { it.x }.toFloat(),
        top = minOf { it.y }.toFloat(),
        right = maxOf { it.x }.toFloat(),
        bottom = maxOf { it.y }.toFloat(),
        corners = take(4).map { PixelPoint(it.x.toFloat(), it.y.toFloat()) }
    )
}
