package com.kopandazavr.datamatrixscanner.scanner

import android.graphics.Point
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader

data class NormalizedPoint(val x: Float, val y: Float)

enum class DetectionHighlight { ACTIVE, DUPLICATE }

data class DetectionBox(
    val points: List<NormalizedPoint>,
    val key: String,
    val imageAspect: Float,
    val highlight: DetectionHighlight = DetectionHighlight.ACTIVE
)

data class DecodedDataMatrix(
    val rawBytes: ByteArray,
    val text: String?,
    val isGs1: Boolean,
    val symbologyIdentifier: String?,
    val contentType: String,
    val box: DetectionBox
)

class DataMatrixAnalyzer(
    private val onDecoded: (List<DecodedDataMatrix>) -> Unit
) : ImageAnalysis.Analyzer {
    @Volatile var fullScreen: Boolean = false
    private var lastAnalysisAt = 0L
    private var frameNumber = 0L

    private val fastReader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.DATA_MATRIX),
            tryHarder = false,
            tryRotate = true,
            tryInvert = false,
            tryDownscale = true,
            maxNumberOfSymbols = 32,
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
        val interval = if (fullScreen) 40L else 75L
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
            val hardEvery = if (fullScreen) 4L else 5L
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
            onDecoded(decoded)
        } catch (_: Throwable) {
            // A malformed frame must never stop the camera analyzer.
        } finally {
            image.close()
        }
    }
}

private fun Point.normalize(width: Int, height: Int) = NormalizedPoint(
    x = (x.toFloat() / width.coerceAtLeast(1)).coerceIn(0f, 1f),
    y = (y.toFloat() / height.coerceAtLeast(1)).coerceIn(0f, 1f)
)
