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
import zxingcpp.BarcodeReader

/**
 * Runs one bounded rescue batch at a time. Each visual variant is decoded by
 * ZXing-C++ and the bundled Google ML Kit model in parallel, while the normal
 * CameraX analyzer keeps consuming fresh frames.
 */
internal class RescueDataMatrixProcessor(
    private val onDecoded: (List<DecodedDataMatrix>) -> Unit,
    private val onPotentialBoxes: (List<DetectionBox>) -> Unit
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val coordinator = Executors.newSingleThreadExecutor()
    private val workers = Executors.newFixedThreadPool(2)
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX)
            .enableAllPotentialBarcodes()
            .build()
    )
    val isRunning: Boolean get() = running.get()

    /** Takes ownership of [source] only when true is returned. */
    fun start(source: Bitmap, mode: ScanEnhancementMode): Boolean {
        if (closed.get() || mode == ScanEnhancementMode.OFF || !running.compareAndSet(false, true)) return false
        val specs = ImageVariantFactory.specs(mode)
        coordinator.execute {
            val accumulated = LinkedHashMap<String, DecodedDataMatrix>()
            val capturedFrame = source.toCapturedFrame()
            try {
                specs.forEach { spec ->
                    if (closed.get()) return@forEach
                    val variant = ImageVariantFactory.create(source, spec.kind)
                    try {
                        val zxingFuture = workers.submit<DecoderPass> {
                            decodeWithZxing(variant.bitmap, spec.binarizer, capturedFrame)
                        }
                        val googleFuture = workers.submit<DecoderPass> {
                            decodeWithGoogle(variant.bitmap, capturedFrame)
                        }
                        val zxingPass = runCatching { zxingFuture.get() }.getOrNull()
                        val googlePass = runCatching { googleFuture.get() }.getOrNull()
                        val decoded = buildList {
                            zxingPass?.decoded?.let(::addAll)
                            googlePass?.decoded?.let(::addAll)
                            val regions = mergeRecoveryRegions(
                                zxingPass.orEmptyRegions() + googlePass.orEmptyRegions(),
                                variant.bitmap.width,
                                variant.bitmap.height,
                                maxRegions = 12
                            )
                            if (regions.isNotEmpty()) {
                                onPotentialBoxes(
                                    regions.mapIndexed { index, region ->
                                        region.toPotentialDetectionBox(
                                            variant.bitmap.width,
                                            variant.bitmap.height,
                                            "rescue:${spec.kind}:$index"
                                        )
                                    }
                                )
                            }
                            regions.forEach { region ->
                                val padded = region.paddedSquare(
                                    variant.bitmap.width,
                                    variant.bitmap.height,
                                    CANDIDATE_CROP_PADDING
                                )
                                val crop = cropBitmap(variant.bitmap, padded) ?: return@forEach
                                try {
                                    addAll(
                                        decodeWithGoogle(
                                            bitmap = crop,
                                            capturedFrame = capturedFrame,
                                            offsetX = padded.left,
                                            offsetY = padded.top,
                                            fullWidth = variant.bitmap.width,
                                            fullHeight = variant.bitmap.height
                                        ).decoded
                                    )
                                } finally {
                                    crop.recycle()
                                }
                            }
                        }
                        decoded.forEach { item ->
                            accumulated.putIfAbsent(Base64.encodeToString(item.rawBytes, Base64.NO_WRAP), item)
                        }
                        // Publish the cumulative result. If the ViewModel drops an older
                        // callback under load, the latest callback still contains every
                        // symbol found earlier in this rescue batch.
                        if (decoded.isNotEmpty()) onDecoded(accumulated.values.toList())
                    } finally {
                        if (variant.owned) variant.bitmap.recycle()
                    }
                }
            } catch (_: Throwable) {
                // One broken enhancement batch must not stop future camera analysis.
            } finally {
                source.recycle()
                running.set(false)
            }
        }
        return true
    }

    private fun decodeWithZxing(
        bitmap: Bitmap,
        binarizer: BarcodeReader.Binarizer,
        capturedFrame: CapturedFrame?
    ): DecoderPass {
        val reader = BarcodeReader(
            BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.DATA_MATRIX),
                tryHarder = true,
                tryRotate = true,
                tryInvert = true,
                tryDownscale = true,
                tryDenoise = true,
                returnErrors = true,
                maxNumberOfSymbols = 32,
                binarizer = binarizer,
                textMode = BarcodeReader.TextMode.PLAIN
            )
        )
        val decoded = mutableListOf<DecodedDataMatrix>()
        val regions = mutableListOf<RecoveryRegion>()
        reader.read(bitmap).forEach { result ->
            val rawPoints = listOf(
                result.position.topLeft,
                result.position.topRight,
                result.position.bottomRight,
                result.position.bottomLeft
            )
            rawPoints.toRegion()?.let(regions::add)
            val bytes = result.bytes ?: return@forEach
            if (result.format != BarcodeReader.Format.DATA_MATRIX || result.error != null) return@forEach
            val points = rawPoints.map { it.normalize(bitmap.width, bitmap.height) }
            decoded += DecodedDataMatrix(
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
        }
        return DecoderPass(
            decoded = decoded.distinctBy { Base64.encodeToString(it.rawBytes, Base64.NO_WRAP) },
            regions = regions
        )
    }

    private fun decodeWithGoogle(
        bitmap: Bitmap,
        capturedFrame: CapturedFrame?,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        fullWidth: Int = bitmap.width,
        fullHeight: Int = bitmap.height
    ): DecoderPass {
        val barcodes = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 2, TimeUnit.SECONDS)
        val decoded = mutableListOf<DecodedDataMatrix>()
        val regions = mutableListOf<RecoveryRegion>()
        barcodes.forEach { barcode ->
            val localPoints = barcode.cornerPoints?.takeIf { it.size >= 4 }?.take(4)
                ?: barcode.boundingBox?.let { box ->
                    listOf(
                        Point(box.left, box.top),
                        Point(box.right, box.top),
                        Point(box.right, box.bottom),
                        Point(box.left, box.bottom)
                    )
                }
                ?: return@forEach
            localPoints.toRegion()?.let(regions::add)
            if (barcode.format != Barcode.FORMAT_DATA_MATRIX) return@forEach
            val bytes = barcode.rawBytes ?: return@forEach
            val points = localPoints.map { point ->
                NormalizedPoint(
                    x = ((offsetX + point.x) / fullWidth.coerceAtLeast(1)).coerceIn(0f, 1f),
                    y = ((offsetY + point.y) / fullHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
                )
            }
            val isGs1 = looksLikeGs1(bytes)
            decoded += DecodedDataMatrix(
                rawBytes = bytes,
                text = barcode.rawValue,
                isGs1 = isGs1,
                symbologyIdentifier = if (isGs1) "]d2" else "]d1",
                contentType = if (isGs1) "GS1" else "TEXT",
                box = DetectionBox(
                    points = points,
                    key = bytes.contentHashCode().toString(),
                    imageAspect = fullWidth.toFloat() / fullHeight.coerceAtLeast(1)
                ),
                capturedFrame = capturedFrame
            )
        }
        return DecoderPass(
            decoded = decoded.distinctBy { Base64.encodeToString(it.rawBytes, Base64.NO_WRAP) },
            regions = regions
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scanner.close()
        coordinator.shutdownNow()
        workers.shutdownNow()
    }
}

private data class DecoderPass(
    val decoded: List<DecodedDataMatrix>,
    val regions: List<RecoveryRegion>
)

private fun DecoderPass?.orEmptyRegions(): List<RecoveryRegion> = this?.regions.orEmpty()

private fun List<Point>.toRegion(): RecoveryRegion? {
    if (size < 4) return null
    return RecoveryRegion(
        left = minOf { it.x }.toFloat(),
        top = minOf { it.y }.toFloat(),
        right = maxOf { it.x }.toFloat(),
        bottom = maxOf { it.y }.toFloat(),
        corners = take(4).map { PixelPoint(it.x.toFloat(), it.y.toFloat()) }
    )
}

private fun cropBitmap(source: Bitmap, region: RecoveryRegion): Bitmap? = runCatching {
    val left = region.left.toInt().coerceIn(0, source.width - 1)
    val top = region.top.toInt().coerceIn(0, source.height - 1)
    val right = region.right.toInt().coerceIn(left + 1, source.width)
    val bottom = region.bottom.toInt().coerceIn(top + 1, source.height)
    Bitmap.createBitmap(source, left, top, right - left, bottom - top)
}.getOrNull()

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
