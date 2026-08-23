package com.kopandazavr.datamatrixscanner.scanner

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.pow
import zxingcpp.BarcodeReader

internal enum class VariantKind {
    ORIGINAL,
    CONTRAST_135,
    CONTRAST_180,
    GAMMA_075,
    GAMMA_135,
    OTSU,
    CLAHE,
    GAMMA_060,
    GAMMA_160,
    SHARPEN
}

internal data class ImageVariantSpec(
    val kind: VariantKind,
    val binarizer: BarcodeReader.Binarizer
)

internal data class ImageVariant(val bitmap: Bitmap, val owned: Boolean)

internal object ImageVariantFactory {
    fun specs(mode: ScanEnhancementMode): List<ImageVariantSpec> {
        if (mode == ScanEnhancementMode.OFF) return emptyList()
        val balanced = listOf(
            ImageVariantSpec(VariantKind.ORIGINAL, BarcodeReader.Binarizer.LOCAL_AVERAGE),
            ImageVariantSpec(VariantKind.CONTRAST_135, BarcodeReader.Binarizer.LOCAL_AVERAGE),
            ImageVariantSpec(VariantKind.CONTRAST_180, BarcodeReader.Binarizer.GLOBAL_HISTOGRAM),
            ImageVariantSpec(VariantKind.GAMMA_075, BarcodeReader.Binarizer.LOCAL_AVERAGE),
            ImageVariantSpec(VariantKind.GAMMA_135, BarcodeReader.Binarizer.LOCAL_AVERAGE),
            ImageVariantSpec(VariantKind.OTSU, BarcodeReader.Binarizer.FIXED_THRESHOLD)
        )
        if (mode == ScanEnhancementMode.BALANCED) return balanced
        return balanced + listOf(
            ImageVariantSpec(VariantKind.CLAHE, BarcodeReader.Binarizer.LOCAL_AVERAGE),
            ImageVariantSpec(VariantKind.GAMMA_060, BarcodeReader.Binarizer.LOCAL_AVERAGE),
            ImageVariantSpec(VariantKind.GAMMA_160, BarcodeReader.Binarizer.GLOBAL_HISTOGRAM),
            ImageVariantSpec(VariantKind.SHARPEN, BarcodeReader.Binarizer.LOCAL_AVERAGE)
        )
    }

    fun create(source: Bitmap, kind: VariantKind): ImageVariant = when (kind) {
        VariantKind.ORIGINAL -> ImageVariant(source, false)
        VariantKind.CONTRAST_135 -> transformed(source, contrastLut(1.35f))
        VariantKind.CONTRAST_180 -> transformed(source, contrastLut(1.8f))
        VariantKind.GAMMA_075 -> transformed(source, gammaLut(.75f))
        VariantKind.GAMMA_135 -> transformed(source, gammaLut(1.35f))
        VariantKind.GAMMA_060 -> transformed(source, gammaLut(.6f))
        VariantKind.GAMMA_160 -> transformed(source, gammaLut(1.6f))
        VariantKind.OTSU -> otsu(source)
        VariantKind.CLAHE -> clahe(source)
        VariantKind.SHARPEN -> sharpen(source)
    }

    private fun contrastLut(factor: Float) = IntArray(256) { value ->
        (128f + factor * (value - 128f)).toInt().coerceIn(0, 255)
    }

    private fun gammaLut(gamma: Float) = IntArray(256) { value ->
        (255f * (value / 255f).pow(gamma)).toInt().coerceIn(0, 255)
    }

    private fun transformed(source: Bitmap, lut: IntArray): ImageVariant {
        val gray = grayscale(source)
        for (index in gray.indices) gray[index] = lut[gray[index]]
        return ImageVariant(toBitmap(gray, source.width, source.height), true)
    }

    private fun otsu(source: Bitmap): ImageVariant {
        val gray = grayscale(source)
        val histogram = IntArray(256)
        gray.forEach { histogram[it] += 1 }
        val total = gray.size
        var sum = 0.0
        histogram.forEachIndexed { value, count -> sum += value * count.toDouble() }
        var backgroundWeight = 0
        var backgroundSum = 0.0
        var bestVariance = -1.0
        var threshold = 127
        for (value in 0..255) {
            backgroundWeight += histogram[value]
            if (backgroundWeight == 0) continue
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0) break
            backgroundSum += value * histogram[value].toDouble()
            val backgroundMean = backgroundSum / backgroundWeight
            val foregroundMean = (sum - backgroundSum) / foregroundWeight
            val difference = backgroundMean - foregroundMean
            val variance = backgroundWeight.toDouble() * foregroundWeight * difference * difference
            if (variance > bestVariance) {
                bestVariance = variance
                threshold = value
            }
        }
        for (index in gray.indices) gray[index] = if (gray[index] <= threshold) 0 else 255
        return ImageVariant(toBitmap(gray, source.width, source.height), true)
    }

    /** Small 8x8-tile CLAHE pass with bilinear interpolation between tile LUTs. */
    private fun clahe(source: Bitmap): ImageVariant {
        val width = source.width
        val height = source.height
        val gray = grayscale(source)
        val tilesX = 8.coerceAtMost(width)
        val tilesY = 8.coerceAtMost(height)
        val tileWidth = (width + tilesX - 1) / tilesX
        val tileHeight = (height + tilesY - 1) / tilesY
        val luts = Array(tilesX * tilesY) { IntArray(256) }

        for (tileY in 0 until tilesY) for (tileX in 0 until tilesX) {
            val left = tileX * tileWidth
            val top = tileY * tileHeight
            val right = (left + tileWidth).coerceAtMost(width)
            val bottom = (top + tileHeight).coerceAtMost(height)
            val histogram = IntArray(256)
            for (y in top until bottom) for (x in left until right) histogram[gray[y * width + x]] += 1
            val pixels = (right - left) * (bottom - top)
            val clipLimit = (2f * pixels / 256f).toInt().coerceAtLeast(1)
            var excess = 0
            for (value in 0..255) if (histogram[value] > clipLimit) {
                excess += histogram[value] - clipLimit
                histogram[value] = clipLimit
            }
            val shared = excess / 256
            val remainder = excess % 256
            for (value in 0..255) histogram[value] += shared + if (value < remainder) 1 else 0
            var cumulative = 0
            val lut = luts[tileY * tilesX + tileX]
            for (value in 0..255) {
                cumulative += histogram[value]
                lut[value] = (cumulative * 255 / pixels.coerceAtLeast(1)).coerceIn(0, 255)
            }
        }

        val output = IntArray(gray.size)
        for (y in 0 until height) {
            val gy = (y + .5f) / tileHeight - .5f
            val y0 = floor(gy).toInt().coerceIn(0, tilesY - 1)
            val y1 = (y0 + 1).coerceAtMost(tilesY - 1)
            val wy = (gy - floor(gy)).coerceIn(0f, 1f)
            for (x in 0 until width) {
                val gx = (x + .5f) / tileWidth - .5f
                val x0 = floor(gx).toInt().coerceIn(0, tilesX - 1)
                val x1 = (x0 + 1).coerceAtMost(tilesX - 1)
                val wx = (gx - floor(gx)).coerceIn(0f, 1f)
                val value = gray[y * width + x]
                val top = luts[y0 * tilesX + x0][value] * (1f - wx) + luts[y0 * tilesX + x1][value] * wx
                val bottom = luts[y1 * tilesX + x0][value] * (1f - wx) + luts[y1 * tilesX + x1][value] * wx
                output[y * width + x] = (top * (1f - wy) + bottom * wy).toInt().coerceIn(0, 255)
            }
        }
        return ImageVariant(toBitmap(output, width, height), true)
    }

    private fun sharpen(source: Bitmap): ImageVariant {
        val width = source.width
        val height = source.height
        val gray = grayscale(source)
        val output = gray.copyOf()
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val index = y * width + x
            val neighbours = gray[index - 1] + gray[index + 1] + gray[index - width] + gray[index + width]
            output[index] = (gray[index] + .5f * (gray[index] - neighbours / 4f)).toInt().coerceIn(0, 255)
        }
        return ImageVariant(toBitmap(output, width, height), true)
    }

    private fun grayscale(source: Bitmap): IntArray {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            pixels[index] = (77 * red + 150 * green + 29 * blue) shr 8
        }
        return pixels
    }

    private fun toBitmap(gray: IntArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(gray.size) { index ->
            val value = gray[index]
            -0x1000000 or (value shl 16) or (value shl 8) or value
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
