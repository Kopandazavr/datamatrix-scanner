package com.kopandazavr.datamatrixscanner.scanner

import android.graphics.Bitmap
import android.graphics.Point
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import zxingcpp.BarcodeReader

/**
 * Runs one bounded rescue batch at a time. Each visual variant is decoded by
 * ZXing-C++ and the bundled Google ML Kit model in parallel, while the normal
 * CameraX analyzer keeps consuming fresh frames.
 */
internal class RescueDataMatrixProcessor(
    private val onDecoded: (List<DecodedDataMatrix>) -> Unit
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val coordinator = Executors.newSingleThreadExecutor()
    private val workers = Executors.newFixedThreadPool(2)
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX)
            .build()
    )
    private val _progress = MutableStateFlow<RescueProgress?>(null)
    val progress: StateFlow<RescueProgress?> = _progress.asStateFlow()

    val isRunning: Boolean get() = running.get()

    /** Takes ownership of [source] only when true is returned. */
    fun start(source: Bitmap, mode: ScanEnhancementMode): Boolean {
        if (closed.get() || mode == ScanEnhancementMode.OFF || !running.compareAndSet(false, true)) return false
        val specs = ImageVariantFactory.specs(mode)
        val total = specs.size * 2
        val completed = AtomicInteger(0)
        _progress.value = RescueProgress(0, total)
        coordinator.execute {
            val seen = HashSet<String>()
            val capturedFrame = source.toCapturedFrame()
            try {
                specs.forEach { spec ->
                    if (closed.get()) return@forEach
                    val variant = ImageVariantFactory.create(source, spec.kind)
                    try {
                        val zxingFuture = workers.submit<List<DecodedDataMatrix>> {
                            try {
                                decodeWithZxing(variant.bitmap, spec.binarizer, capturedFrame)
                            } finally {
                                publishProgress(completed.incrementAndGet(), total)
                            }
                        }
                        val googleFuture = workers.submit<List<DecodedDataMatrix>> {
                            try {
                                decodeWithGoogle(variant.bitmap, capturedFrame)
                            } finally {
                                publishProgress(completed.incrementAndGet(), total)
                            }
                        }
                        val decoded = buildList {
                            runCatching { zxingFuture.get() }.getOrNull()?.let(::addAll)
                            runCatching { googleFuture.get() }.getOrNull()?.let(::addAll)
                        }.filter { seen.add(Base64.encodeToString(it.rawBytes, Base64.NO_WRAP)) }
                        if (decoded.isNotEmpty()) onDecoded(decoded)
                    } finally {
                        if (variant.owned) variant.bitmap.recycle()
                    }
                }
            } catch (_: Throwable) {
                // One broken enhancement batch must not stop future camera analysis.
            } finally {
                source.recycle()
                running.set(false)
                _progress.value = null
            }
        }
        return true
    }

    private fun publishProgress(completed: Int, total: Int) {
        _progress.value = RescueProgress(completed.coerceAtMost(total), total)
    }

    private fun decodeWithZxing(
        bitmap: Bitmap,
        binarizer: BarcodeReader.Binarizer,
        capturedFrame: CapturedFrame?
    ): List<DecodedDataMatrix> {
        val reader = BarcodeReader(
            BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.DATA_MATRIX),
                tryHarder = true,
                tryRotate = true,
                tryInvert = true,
                tryDownscale = true,
                tryDenoise = true,
                maxNumberOfSymbols = 32,
                binarizer = binarizer,
                textMode = BarcodeReader.TextMode.PLAIN
            )
        )
        return reader.read(bitmap).mapNotNull { result ->
            val bytes = result.bytes ?: return@mapNotNull null
            if (result.format != BarcodeReader.Format.DATA_MATRIX || result.error != null) return@mapNotNull null
            val points = listOf(
                result.position.topLeft,
                result.position.topRight,
                result.position.bottomRight,
                result.position.bottomLeft
            ).map { it.normalize(bitmap.width, bitmap.height) }
            DecodedDataMatrix(
                rawBytes = bytes,
                text = result.text,
                isGs1 = result.contentType == BarcodeReader.ContentType.GS1,
                symbologyIdentifier = result.symbologyIdentifier,
                contentType = result.contentType.name,
                box = DetectionBox(
                    points = points,
                    key = bytes.contentHashCode().toString(),
                    imageAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                ),
                capturedFrame = capturedFrame
            )
        }.distinctBy { Base64.encodeToString(it.rawBytes, Base64.NO_WRAP) }
    }

    private fun decodeWithGoogle(bitmap: Bitmap, capturedFrame: CapturedFrame?): List<DecodedDataMatrix> {
        val barcodes = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 2, TimeUnit.SECONDS)
        return barcodes.mapNotNull { barcode ->
            if (barcode.format != Barcode.FORMAT_DATA_MATRIX) return@mapNotNull null
            val bytes = barcode.rawBytes ?: return@mapNotNull null
            val points = barcode.cornerPoints?.takeIf { it.size >= 4 }?.take(4)
                ?: barcode.boundingBox?.let { box ->
                    listOf(
                        Point(box.left, box.top),
                        Point(box.right, box.top),
                        Point(box.right, box.bottom),
                        Point(box.left, box.bottom)
                    )
                }
                ?: return@mapNotNull null
            val isGs1 = looksLikeGs1(bytes)
            DecodedDataMatrix(
                rawBytes = bytes,
                text = barcode.rawValue,
                isGs1 = isGs1,
                symbologyIdentifier = if (isGs1) "]d2" else "]d1",
                contentType = if (isGs1) "GS1" else "TEXT",
                box = DetectionBox(
                    points = points.map { it.normalize(bitmap.width, bitmap.height) },
                    key = bytes.contentHashCode().toString(),
                    imageAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                ),
                capturedFrame = capturedFrame
            )
        }.distinctBy { Base64.encodeToString(it.rawBytes, Base64.NO_WRAP) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scanner.close()
        coordinator.shutdownNow()
        workers.shutdownNow()
        _progress.value = null
    }
}

private fun Bitmap.toCapturedFrame(): CapturedFrame? = try {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, 90, stream)
    val jpeg = stream.toByteArray()
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(jpeg)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    CapturedFrame(jpeg, width, height, hash)
} catch (_: Throwable) {
    null
}

private fun Point.normalize(width: Int, height: Int) = NormalizedPoint(
    x = (x.toFloat() / width.coerceAtLeast(1)).coerceIn(0f, 1f),
    y = (y.toFloat() / height.coerceAtLeast(1)).coerceIn(0f, 1f)
)
