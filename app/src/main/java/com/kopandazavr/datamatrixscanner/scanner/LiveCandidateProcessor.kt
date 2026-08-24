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
import java.util.concurrent.atomic.AtomicReference

/**
 * Fast live path for white candidate boxes.
 *
 * A candidate found by ZXing-C++ is isolated immediately and sent to the bundled
 * Google ML Kit Data Matrix decoder from the very same oriented camera frame.
 * This path is deliberately independent from the heavy transformed-image rescue,
 * so a background enhancement batch can never delay white -> green promotion.
 * While one batch is running, only the newest camera batch is retained.
 */
internal class LiveCandidateProcessor(
    private val onDecoded: (List<DecodedDataMatrix>) -> Unit,
    private val onPotentialBoxes: (List<DetectionBox>) -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val latest = AtomicReference<LiveCandidateBatch?>(null)
    private val executor = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX)
            .enableAllPotentialBarcodes()
            .build()
    )

    /** Always takes ownership of [source]. */
    fun submit(source: Bitmap, seedRegions: List<RecoveryRegion>) {
        if (closed.get()) {
            source.recycle()
            return
        }
        latest.getAndSet(LiveCandidateBatch(source, seedRegions))?.source?.recycle()
        schedule()
    }

    private fun schedule() {
        if (closed.get()) return
        if (running.compareAndSet(false, true)) {
            executor.execute(::drain)
        }
    }

    private fun drain() {
        try {
            while (!closed.get()) {
                val batch = latest.getAndSet(null) ?: break
                process(batch)
            }
        } finally {
            running.set(false)
            if (!closed.get() && latest.get() != null) schedule()
        }
    }

    private fun process(batch: LiveCandidateBatch) {
        val source = batch.source
        val accumulated = LinkedHashMap<String, DecodedDataMatrix>()
        var capturedFrame: CapturedFrame? = null

        fun publish(pass: LiveGooglePass) {
            if (pass.decoded.isEmpty()) return
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
            val seedRegions = mergeRecoveryRegions(
                batch.seedRegions,
                source.width,
                source.height,
                maxRegions = 12
            )

            // Priority 1: every white ZXing candidate goes straight to ML Kit.
            // Do this before any additional full-frame work so the common case of
            // two clearly visible packs is resolved with minimum latency.
            seedRegions.forEach { region ->
                decodeRegion(source, region)?.let(::publish)
            }

            // Priority 2: one cheap original full-frame ML Kit pass can contribute
            // additional potential regions that ZXing did not expose.
            val fullPass = decodeWithGoogle(source)
            publish(fullPass)

            val allRegions = mergeRecoveryRegions(
                seedRegions + fullPass.regions,
                source.width,
                source.height,
                maxRegions = 12
            )
            if (allRegions.isNotEmpty()) {
                onPotentialBoxes(
                    allRegions.mapIndexed { index, region ->
                        region.toPotentialDetectionBox(source.width, source.height, "live-google:$index")
                    }
                )
            }

            // Google can return a potential box without data. Isolate those too.
            mergeRecoveryRegions(fullPass.regions, source.width, source.height, maxRegions = 8)
                .forEach { region -> decodeRegion(source, region)?.let(::publish) }
        } catch (_: Throwable) {
            // A bad candidate batch must not affect the camera analyzer.
        } finally {
            source.recycle()
        }
    }

    private fun decodeRegion(source: Bitmap, region: RecoveryRegion): LiveGooglePass? {
        val padded = region.paddedSquare(source.width, source.height, CANDIDATE_CROP_PADDING)
        val crop = cropLiveBitmap(source, padded) ?: return null
        return try {
            decodeWithGoogle(
                bitmap = crop,
                offsetX = padded.left,
                offsetY = padded.top,
                fullWidth = source.width,
                fullHeight = source.height
            )
        } finally {
            crop.recycle()
        }
    }

    private fun decodeWithGoogle(
        bitmap: Bitmap,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        fullWidth: Int = bitmap.width,
        fullHeight: Int = bitmap.height
    ): LiveGooglePass {
        val barcodes = runCatching {
            Tasks.await(
                scanner.process(InputImage.fromBitmap(bitmap, 0)),
                1_200,
                TimeUnit.MILLISECONDS
            )
        }.getOrElse { return LiveGooglePass(emptyList(), emptyList()) }

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

            localPoints.toLiveRegion(offsetX, offsetY)?.let(regions::add)
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
                )
            )
        }
        return LiveGooglePass(
            decoded = decoded.distinctBy { Base64.encodeToString(it.rawBytes, Base64.NO_WRAP) },
            regions = regions
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        latest.getAndSet(null)?.source?.recycle()
        scanner.close()
        executor.shutdownNow()
    }
}

private data class LiveCandidateBatch(
    val source: Bitmap,
    val seedRegions: List<RecoveryRegion>
)

private data class LiveGooglePass(
    val decoded: List<DecodedDataMatrix>,
    val regions: List<RecoveryRegion>
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

private fun cropLiveBitmap(source: Bitmap, region: RecoveryRegion): Bitmap? = runCatching {
    val left = region.left.toInt().coerceIn(0, source.width - 1)
    val top = region.top.toInt().coerceIn(0, source.height - 1)
    val right = region.right.toInt().coerceIn(left + 1, source.width)
    val bottom = region.bottom.toInt().coerceIn(top + 1, source.height)
    Bitmap.createBitmap(source, left, top, right - left, bottom - top)
}.getOrNull()

private fun Bitmap.toLiveCapturedFrame(): CapturedFrame? = try {
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
