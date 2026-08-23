package com.kopandazavr.datamatrixscanner.scanner

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import zxingcpp.BarcodeReader

/**
 * Slow, deliberate decoder for damaged photographs. Camera decoding stays lean;
 * imported photos get several independent contrast/binarization passes.
 */
class PhotoRecoveryDecoder {
    suspend fun decode(resolver: ContentResolver, uri: Uri): List<DecodedDataMatrix> = withContext(Dispatchers.Default) {
        val original = loadBitmap(resolver, uri) ?: return@withContext emptyList()
        try {
            val processingBase = fitWithin(original, 2_600)
            val variants = buildList {
                add(original)
                if (processingBase !== original) add(processingBase)
                add(adjust(processingBase, contrast = 1.35f))
                add(adjust(processingBase, contrast = 1.8f))
            }
            val found = mutableListOf<DecodedDataMatrix>()
            try {
                val binarizers = listOf(
                    BarcodeReader.Binarizer.LOCAL_AVERAGE,
                    BarcodeReader.Binarizer.GLOBAL_HISTOGRAM,
                    BarcodeReader.Binarizer.FIXED_THRESHOLD
                )
                variants.forEach { bitmap ->
                    binarizers.forEach { binarizer ->
                        val reader = BarcodeReader(
                            BarcodeReader.Options(
                                formats = setOf(BarcodeReader.Format.DATA_MATRIX),
                                tryHarder = true,
                                tryRotate = true,
                                tryInvert = true,
                                tryDownscale = true,
                                tryDenoise = true,
                                binarizer = binarizer,
                                maxNumberOfSymbols = 64,
                                textMode = BarcodeReader.TextMode.PLAIN
                            )
                        )
                        reader.read(bitmap, Rect(0, 0, bitmap.width, bitmap.height)).forEach { result ->
                            val bytes = result.bytes ?: return@forEach
                            if (result.format != BarcodeReader.Format.DATA_MATRIX || result.error != null) return@forEach
                            if (found.any { it.rawBytes.contentEquals(bytes) }) return@forEach
                            val points = listOf(
                                result.position.topLeft,
                                result.position.topRight,
                                result.position.bottomRight,
                                result.position.bottomLeft
                            ).map { point ->
                                NormalizedPoint(
                                    (point.x.toFloat() / bitmap.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                    (point.y.toFloat() / bitmap.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                )
                            }
                            found += DecodedDataMatrix(
                                rawBytes = bytes,
                                text = result.text,
                                isGs1 = result.contentType == BarcodeReader.ContentType.GS1,
                                symbologyIdentifier = result.symbologyIdentifier,
                                contentType = result.contentType.name,
                                box = DetectionBox(
                                    points = points,
                                    key = bytes.contentHashCode().toString(),
                                    imageAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                                )
                            )
                        }
                    }
                }
            } finally {
                variants.distinctBy(System::identityHashCode).forEach { if (it !== original && !it.isRecycled) it.recycle() }
            }
            found
        } finally {
            if (!original.isRecycled) original.recycle()
        }
    }

    private fun loadBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 4_096) sample *= 2
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

    private fun adjust(source: Bitmap, contrast: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val offset = 128f * (1f - contrast)
        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        Canvas(output).drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        })
        return output
    }
}
