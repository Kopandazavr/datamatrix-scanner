package com.kopandazavr.datamatrixscanner.scanner

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.datamatrix.DataMatrixReader as JavaDataMatrixReader
import java.nio.charset.StandardCharsets
import java.util.EnumMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import zxingcpp.BarcodeReader

/**
 * Deliberately expensive recovery pipeline for imported photographs. It keeps
 * the original pixels for tiled/candidate passes and streams transformations so
 * that an exhaustive search does not keep dozens of full-size bitmaps alive.
 */
class PhotoRecoveryDecoder : AutoCloseable {
    private val googleScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX)
            .enableAllPotentialBarcodes()
            .build()
    )

    suspend fun decode(resolver: ContentResolver, uri: Uri): List<DecodedDataMatrix> =
        withContext(Dispatchers.Default) {
            val original = loadBitmap(resolver, uri) ?: return@withContext emptyList()
            val found = LinkedHashMap<String, DecodedDataMatrix>()
            val proposals = mutableListOf<RecoveryRegion>()
            try {
                // The complete composition is always sent to every decoder. Large
                // originals are represented by a 4096px full-frame pass, while the
                // tile pyramid below retains their native module resolution.
                val fullFrame = fitWithin(original, 4_096)
                try {
                    scanBitmap(
                        bitmap = fullFrame,
                        specs = ImageVariantFactory.photoSpecs(),
                        found = found,
                        proposals = proposals,
                        scaleX = original.width.toFloat() / fullFrame.width,
                        scaleY = original.height.toFloat() / fullFrame.height,
                        includeJava = true
                    )
                } finally {
                    if (fullFrame !== original) fullFrame.recycle()
                }

                // Overlapping tiles make every part of the photo cross the ML Kit
                // centre point and preserve tiny modules from the original image.
                val tileSpecs = listOf(
                    ImageVariantSpec(VariantKind.ORIGINAL, BarcodeReader.Binarizer.LOCAL_AVERAGE),
                    ImageVariantSpec(VariantKind.CLAHE, BarcodeReader.Binarizer.LOCAL_AVERAGE),
                    ImageVariantSpec(VariantKind.OTSU, BarcodeReader.Binarizer.FIXED_THRESHOLD)
                )
                overlappingTiles(original.width, original.height).forEach { tileRegion ->
                    val tile = crop(original, tileRegion) ?: return@forEach
                    try {
                        scanBitmap(
                            bitmap = tile,
                            specs = tileSpecs,
                            found = found,
                            proposals = proposals,
                            offsetX = tileRegion.left,
                            offsetY = tileRegion.top
                        )
                    } finally {
                        tile.recycle()
                    }
                }

                // Candidate areas from both ZXing-C++ (including error positions)
                // and ML Kit potential barcodes receive the full enhancement set.
                mergeRecoveryRegions(proposals, original.width, original.height, maxRegions = 40)
                    .forEach { region -> scanCandidate(original, region, found) }

                found.values.toList()
            } finally {
                original.recycle()
            }
        }

    private fun scanCandidate(
        original: Bitmap,
        region: RecoveryRegion,
        found: LinkedHashMap<String, DecodedDataMatrix>
    ) {
        val cropRegion = region.paddedSquare(
            original.width,
            original.height,
            CANDIDATE_CROP_PADDING
        )
        val candidate = crop(original, cropRegion) ?: return
        try {
            val bases = buildList {
                add(candidate to false)
                upscaleToMinSide(candidate, 640, 1_600, filtered = false)?.let { add(it to true) }
                addQuietZone(candidate, .12f)?.let { add(it to true) }
                rectify(original, region)?.let { add(it to true) }
            }
            try {
                bases.forEachIndexed { index, (base, _) ->
                    scanBitmap(
                        bitmap = base,
                        specs = ImageVariantFactory.photoSpecs(),
                        found = found,
                        proposals = mutableListOf(),
                        includeJava = true,
                        purePass = index == bases.lastIndex && region.corners.size == 4
                    )
                }
            } finally {
                bases.drop(1).forEach { (bitmap, owned) -> if (owned) bitmap.recycle() }
            }
        } finally {
            candidate.recycle()
        }
    }

    private fun scanBitmap(
        bitmap: Bitmap,
        specs: List<ImageVariantSpec>,
        found: LinkedHashMap<String, DecodedDataMatrix>,
        proposals: MutableList<RecoveryRegion>,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        includeJava: Boolean = false,
        purePass: Boolean = false
    ) {
        specs.forEachIndexed { index, spec ->
            val variant = ImageVariantFactory.create(bitmap, spec.kind)
            try {
                val zxingReader = BarcodeReader(
                    BarcodeReader.Options(
                        formats = setOf(BarcodeReader.Format.DATA_MATRIX),
                        tryHarder = true,
                        tryRotate = true,
                        tryInvert = true,
                        tryDownscale = true,
                        tryDenoise = true,
                        isPure = purePass,
                        returnErrors = true,
                        binarizer = spec.binarizer,
                        maxNumberOfSymbols = 128,
                        textMode = BarcodeReader.TextMode.PLAIN
                    )
                )
                zxingReader.read(variant.bitmap, Rect(0, 0, variant.bitmap.width, variant.bitmap.height)).forEach { result ->
                    val points = listOf(
                        result.position.topLeft,
                        result.position.topRight,
                        result.position.bottomRight,
                        result.position.bottomLeft
                    )
                    regionFrom(points, offsetX, offsetY, scaleX, scaleY)?.let(proposals::add)
                    val bytes = result.bytes
                    if (bytes != null && result.format == BarcodeReader.Format.DATA_MATRIX && result.error == null) {
                        putDecoded(
                            found, bytes, result.text,
                            result.contentType == BarcodeReader.ContentType.GS1,
                            result.symbologyIdentifier, result.contentType.name,
                            points, variant.bitmap.width, variant.bitmap.height
                        )
                    }
                }

                val googleResults = runCatching {
                    Tasks.await(
                        googleScanner.process(InputImage.fromBitmap(variant.bitmap, 0)),
                        4,
                        TimeUnit.SECONDS
                    )
                }.getOrDefault(emptyList())
                googleResults.forEach { barcode ->
                    val points = barcodePoints(barcode)
                    regionFrom(points, offsetX, offsetY, scaleX, scaleY)?.let(proposals::add)
                    val bytes = barcode.rawBytes
                    if (barcode.format == Barcode.FORMAT_DATA_MATRIX && bytes != null && points.isNotEmpty()) {
                        val isGs1 = looksLikeGs1(bytes)
                        putDecoded(
                            found, bytes, barcode.rawValue, isGs1,
                            if (isGs1) "]d2" else "]d1",
                            if (isGs1) "GS1" else "TEXT",
                            points, variant.bitmap.width, variant.bitmap.height
                        )
                    }
                }

                if (includeJava && index == 0) decodeWithJava(variant.bitmap, purePass)?.let { decoded ->
                    found.putIfAbsent(Base64.encodeToString(decoded.rawBytes, Base64.NO_WRAP), decoded)
                }
            } finally {
                if (variant.owned) variant.bitmap.recycle()
            }
        }
    }

    private fun decodeWithJava(bitmap: Bitmap, pure: Boolean): DecodedDataMatrix? = runCatching {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.POSSIBLE_FORMATS, listOf(com.google.zxing.BarcodeFormat.DATA_MATRIX))
            put(DecodeHintType.TRY_HARDER, true)
            put(DecodeHintType.ALSO_INVERTED, true)
            if (pure) put(DecodeHintType.PURE_BARCODE, true)
        }
        val result = JavaDataMatrixReader().decode(binary, hints)
        val bytes = result.text.toByteArray(StandardCharsets.ISO_8859_1)
        val symbology = result.resultMetadata?.get(ResultMetadataType.SYMBOLOGY_IDENTIFIER) as? String
        val points = result.resultPoints.orEmpty().map { Point(it.x.toInt(), it.y.toInt()) }
        val normalized = if (points.size >= 4) points.take(4).map { it.photoNormalize(bitmap.width, bitmap.height) } else listOf(
            NormalizedPoint(0f, 0f), NormalizedPoint(1f, 0f),
            NormalizedPoint(1f, 1f), NormalizedPoint(0f, 1f)
        )
        DecodedDataMatrix(
            rawBytes = bytes,
            text = result.text,
            isGs1 = symbology == "]d2",
            symbologyIdentifier = symbology ?: "]d1",
            contentType = if (symbology == "]d2") "GS1" else "TEXT",
            box = DetectionBox(
                points = normalized,
                key = bytes.contentHashCode().toString(),
                imageAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            )
        )
    }.getOrNull()

    private fun putDecoded(
        found: LinkedHashMap<String, DecodedDataMatrix>,
        bytes: ByteArray,
        text: String?,
        isGs1: Boolean,
        symbologyIdentifier: String?,
        contentType: String,
        points: List<Point>,
        width: Int,
        height: Int
    ) {
        val normalized = if (points.size >= 4) points.take(4).map { it.photoNormalize(width, height) } else listOf(
            NormalizedPoint(0f, 0f), NormalizedPoint(1f, 0f),
            NormalizedPoint(1f, 1f), NormalizedPoint(0f, 1f)
        )
        val item = DecodedDataMatrix(
            rawBytes = bytes,
            text = text,
            isGs1 = isGs1,
            symbologyIdentifier = symbologyIdentifier,
            contentType = contentType,
            box = DetectionBox(
                points = normalized,
                key = bytes.contentHashCode().toString(),
                imageAspect = width.toFloat() / height.coerceAtLeast(1)
            )
        )
        found.putIfAbsent(Base64.encodeToString(bytes, Base64.NO_WRAP), item)
    }

    private fun barcodePoints(barcode: Barcode): List<Point> =
        barcode.cornerPoints?.takeIf { it.size >= 4 }?.take(4)
            ?: barcode.boundingBox?.let { box ->
                listOf(
                    Point(box.left, box.top), Point(box.right, box.top),
                    Point(box.right, box.bottom), Point(box.left, box.bottom)
                )
            }
            ?: emptyList()

    private fun regionFrom(
        points: List<Point>, offsetX: Float, offsetY: Float, scaleX: Float, scaleY: Float
    ): RecoveryRegion? {
        if (points.size < 4) return null
        val mapped = points.take(4).map { PixelPoint(offsetX + it.x * scaleX, offsetY + it.y * scaleY) }
        return RecoveryRegion(
            left = mapped.minOf { it.x }, top = mapped.minOf { it.y },
            right = mapped.maxOf { it.x }, bottom = mapped.maxOf { it.y },
            corners = mapped
        )
    }

    private fun crop(source: Bitmap, region: RecoveryRegion): Bitmap? = runCatching {
        val left = region.left.toInt().coerceIn(0, source.width - 1)
        val top = region.top.toInt().coerceIn(0, source.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, source.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, source.height)
        Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }.getOrNull()

    private fun rectify(source: Bitmap, region: RecoveryRegion): Bitmap? = runCatching {
        if (region.corners.size != 4) return null
        val longest = maxOf(region.width, region.height).toInt().coerceIn(256, 1_280)
        val quiet = (longest * .12f).toInt().coerceAtLeast(16)
        val size = longest + quiet * 2
        val sourcePoints = region.corners.flatMap { listOf(it.x, it.y) }.toFloatArray()
        val destinationPoints = floatArrayOf(
            quiet.toFloat(), quiet.toFloat(),
            (size - quiet).toFloat(), quiet.toFloat(),
            (size - quiet).toFloat(), (size - quiet).toFloat(),
            quiet.toFloat(), (size - quiet).toFloat()
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 4)) return null
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
        }
    }.getOrNull()

    private fun addQuietZone(source: Bitmap, fraction: Float): Bitmap? = runCatching {
        val quiet = (maxOf(source.width, source.height) * fraction).toInt().coerceAtLeast(8)
        Bitmap.createBitmap(source.width + quiet * 2, source.height + quiet * 2, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, quiet.toFloat(), quiet.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
    }.getOrNull()

    private fun upscaleToMinSide(source: Bitmap, minSide: Int, maxSide: Int, filtered: Boolean): Bitmap? {
        val shortest = minOf(source.width, source.height)
        if (shortest >= minSide) return null
        val requestedScale = minSide.toFloat() / shortest.coerceAtLeast(1)
        val limitedScale = minOf(requestedScale, maxSide.toFloat() / maxOf(source.width, source.height))
        if (limitedScale <= 1f) return null
        return Bitmap.createScaledBitmap(
            source,
            (source.width * limitedScale).toInt().coerceAtLeast(1),
            (source.height * limitedScale).toInt().coerceAtLeast(1),
            filtered
        )
    }

    private fun loadBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val largest = maxOf(info.size.width, info.size.height)
                    if (largest > 8_192) {
                        val scale = 8_192f / largest
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            }.getOrNull()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 8_192) sample *= 2
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    }

    private fun fitWithin(source: Bitmap, maxSide: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxSide) return source
        val scale = maxSide.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    override fun close() = googleScanner.close()
}

private fun Point.photoNormalize(width: Int, height: Int) = NormalizedPoint(
    x = (x.toFloat() / width.coerceAtLeast(1)).coerceIn(0f, 1f),
    y = (y.toFloat() / height.coerceAtLeast(1)).coerceIn(0f, 1f)
)
