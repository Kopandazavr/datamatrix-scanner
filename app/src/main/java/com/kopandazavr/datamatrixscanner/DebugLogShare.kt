package com.kopandazavr.datamatrixscanner

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun saveDebugLog(context: Context, text: String, sessionLog: Boolean = false): Boolean {
    return saveTextDownload(context, text, if (sessionLog) "DataMatrix_session" else "DataMatrix_debug")
}

internal suspend fun saveStatistics(
    context: Context,
    text: String
): Boolean {
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return saveTextDownload(context, text, "DataMatrix_statistics_full_technical_v$version")
}

private suspend fun saveTextDownload(context: Context, text: String, filenamePrefix: String): Boolean {
    if (text.isBlank()) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return withContext(Dispatchers.IO) {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@withContext false
            runCatching {
                val name = "${filenamePrefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
                File(directory, name).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                true
            }.getOrDefault(false)
        }
    }

    val uri = withContext(Dispatchers.IO) {
        val name = "${filenamePrefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DataMatrixScanner")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        runCatching {
            resolver.openOutputStream(created, "w")!!.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            resolver.update(created, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            created
        }.getOrElse {
            runCatching { resolver.delete(created, null, null) }
            null
        }
    } ?: return false
    return uri.toString().isNotBlank()
}
