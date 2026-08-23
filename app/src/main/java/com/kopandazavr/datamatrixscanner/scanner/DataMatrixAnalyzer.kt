package com.kopandazavr.datamatrixscanner.scanner

import android.graphics.Point
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Base64
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.StateFlow
import zxingcpp.BarcodeReader

data class NormalizedPoint(val x: Float, val y: Float)

enum class DetectionHighlight { ACTIVE, DUPLICATE }

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
    private val onDecoded: (List<DecodedDataMatrix>) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {
    @Volatile var fullScreen: Boolean = false
    @Volatile var visibleHeightFraction: Float = 1f
    @Volatile var enhancementMode: ScanEnhancementMode = ScanEnhancementMode.BALANCED
    @Volatile var lastNovelScanAt: Long = System.currentTimeMillis()
    private var lastAnalysisAt = 0L
    private var lastRescueStartedAt = 0L
    private var frameNumber = 0L
    private val rescueProcessor = RescueDataMatrixProcessor(onDecoded)
    val rescueProgress: StateFlow<RescueProgress?> = rescueProcessor.progress
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
            maxNumberOfSymbols = 32,
            textMode = BarcodeReader.TextMode.PLAIN
        )
    )

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
            // The physical PreviewView always stays large. In compact mode Compose clips
            // its centre, so apply the matching centre crop only to ImageAnalysis without
            // asking CameraController to rebuild its use cases.
            val crop = centeredVisibleCrop(image.cropRect, rotation, visibleHeightFraction)
            image.setCropRect(crop)
            val outputWidth = if (rotation == 90 || rotation == 270) crop.height() else crop.width()
            val outputHeight = if (rotation == 90 || rotation == 270) crop.width() else crop.height()
            frameNumber += 1
            val fastResults = fastReader.read(image)
            val hardEvery = 3L
            val results = if (frameNumber % hardEvery == 0L && !rescueProcessor.isRunning) {
                fastResults + hardReader.read(image)
            } else fastResults
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
            val mode = enhancementMode
            if (RescueScanPolicy.shouldStart(now, lastNovelScanAt, lastRescueStartedAt, rescueProcessor.isRunning, mode)) {
                captureVisibleBitmap(image)?.let { snapshot ->
                    if (rescueProcessor.start(snapshot, mode)) {
                        lastRescueStartedAt = now
                    } else {
                        snapshot.recycle()
                    }
                }
            }
        } catch (_: Throwable) {
            // A malformed frame must never stop the camera analyzer.
        } finally {
            image.close()
        }
    }

    override fun close() {
        rescueProcessor.close()
    }
}

private fun centeredVisibleCrop(source: Rect, rotation: Int, heightFraction: Float): Rect {
    val fraction = heightFraction.coerceIn(0.1f, 1f)
    if (fraction >= .999f) return Rect(source)
    return if (rotation == 90 || rotation == 270) {
        val targetWidth = (source.width() * fraction).toInt().coerceAtLeast(1)
        val left = source.left + (source.width() - targetWidth) / 2
        Rect(left, source.top, left + targetWidth, source.bottom)
    } else {
        val targetHeight = (source.height() * fraction).toInt().coerceAtLeast(1)
        val top = source.top + (source.height() - targetHeight) / 2
        Rect(source.left, top, source.right, top + targetHeight)
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
