package com.kopandazavr.datamatrixscanner.scanner

import android.graphics.Point
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
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
) : ImageAnalysis.Analyzer {
    @Volatile var fullScreen: Boolean = false
    private var lastAnalysisAt = 0L
    private var frameNumber = 0L
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
            // BarcodeReader honours ImageProxy.cropRect. CameraController keeps that crop
            // aligned with PreviewView's FILL_CENTER viewport, so decoding and overlay use
            // exactly the part of the sensor frame visible to the user.
            val crop = image.cropRect
            val rotation = image.imageInfo.rotationDegrees
            val outputWidth = if (rotation == 90 || rotation == 270) crop.height() else crop.width()
            val outputHeight = if (rotation == 90 || rotation == 270) crop.width() else crop.height()
            frameNumber += 1
            val fastResults = fastReader.read(image)
            val hardEvery = 3L
            val results = if (frameNumber % hardEvery == 0L) fastResults + hardReader.read(image) else fastResults
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
        } catch (_: Throwable) {
            // A malformed frame must never stop the camera analyzer.
        } finally {
            image.close()
        }
    }
}

private fun captureVisibleFrame(image: ImageProxy): CapturedFrame? = try {
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
