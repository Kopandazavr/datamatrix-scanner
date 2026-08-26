package com.kopandazavr.datamatrixscanner

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun saveCameraPhoto(
    context: Context,
    imageCapture: ImageCapture,
    onResult: (String) -> Unit
) {
    val name = "DataMatrix_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
        }
    }
    val output = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values
    ).build()
    imageCapture.takePicture(
        output,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onResult("Фото сохранено в Галерею")
            }

            override fun onError(exception: ImageCaptureException) {
                onResult("Не удалось сохранить фото")
            }
        }
    )
}

suspend fun saveJpegToGallery(
    context: Context,
    jpeg: ByteArray,
    prefix: String = "DataMatrix"
): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val name = "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
        ?: return@withContext false
    val written = runCatching {
        resolver.openOutputStream(uri, "w")?.use { it.write(jpeg) } ?: error("no output stream")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
        }
        true
    }.getOrElse {
        runCatching { resolver.delete(uri, null, null) }
        false
    }
    written
}

suspend fun bitmapToJpeg(bitmap: android.graphics.Bitmap, quality: Int = 94): ByteArray? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        runCatching {
            java.io.ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, stream))
                stream.toByteArray()
            }
        }.getOrNull()
    }
