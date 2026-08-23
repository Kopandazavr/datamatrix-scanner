package com.kopandazavr.datamatrixscanner.scanner

import android.graphics.Point
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader

data class NormalizedPoint(val x: Float, val y: Float)

data class DetectionBox(
    val points: List<NormalizedPoint>,
    val key: String,
    val imageAspect: Float
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

    private val reader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.DATA_MATRIX),
            tryHarder = true,
            tryRotate = true,
            tryInvert = true,
            tryDownscale = true,
            maxNumberOfSymbols = 32,
            textMode = BarcodeReader.TextMode.PLAIN
        )
    )

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        val interval = if (fullScreen) 70L else 150L
        if (now - lastAnalysisAt < interval) {
            image.close()
            return
        }
        lastAnalysisAt = now
        try {
            val rotation = image.imageInfo.rotationDegrees
            val outputWidth = if (rotation == 90 || rotation == 270) image.height else image.width
            val outputHeight = if (rotation == 90 || rotation == 270) image.width else image.height
            val decoded = reader.read(image).mapNotNull { result ->
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
            if (decoded.isNotEmpty()) onDecoded(decoded)
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
