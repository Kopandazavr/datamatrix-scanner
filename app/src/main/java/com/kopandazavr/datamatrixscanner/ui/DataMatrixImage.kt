package com.kopandazavr.datamatrixscanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.datamatrix.DataMatrixWriter
import com.google.zxing.datamatrix.encoder.SymbolShapeHint
import java.nio.charset.StandardCharsets

@Composable
fun DataMatrixImage(rawBytes: ByteArray, isGs1: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(rawBytes.contentHashCode(), isGs1, size) {
        generateDataMatrix(rawBytes, isGs1, (size.value * 3).toInt().coerceAtLeast(180))
    }
    Box(modifier.background(Color.White).padding(5.dp)) {
        Image(
            painter = BitmapPainter(bitmap.asImageBitmap(), filterQuality = androidx.compose.ui.graphics.FilterQuality.None),
            contentDescription = "Data Matrix",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size - 10.dp)
        )
    }
}

private fun generateDataMatrix(rawBytes: ByteArray, isGs1: Boolean, pixels: Int): Bitmap {
    val payload = rawBytes.toString(StandardCharsets.ISO_8859_1)
    val hints = mutableMapOf<EncodeHintType, Any>(
        EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        EncodeHintType.MARGIN to 4,
        EncodeHintType.DATA_MATRIX_SHAPE to SymbolShapeHint.FORCE_SQUARE
    )
    if (isGs1) hints[EncodeHintType.GS1_FORMAT] = true
    val matrix = DataMatrixWriter().encode(payload, BarcodeFormat.DATA_MATRIX, pixels, pixels, hints)
    return Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888).also { bitmap ->
        val pixelsArray = IntArray(pixels * pixels)
        for (y in 0 until pixels) for (x in 0 until pixels) {
            pixelsArray[y * pixels + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        bitmap.setPixels(pixelsArray, 0, pixels, 0, 0, pixels, pixels)
    }
}
