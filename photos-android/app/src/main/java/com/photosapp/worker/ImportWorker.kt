package com.photosapp.worker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.ZipInputStream

class ImportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_ZIP_URI = "KEY_ZIP_URI"
        const val NOTIFICATION_ID = 1002
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uriString = inputData.getString(KEY_ZIP_URI) ?: return@withContext Result.failure()
        val zipUri = Uri.parse(uriString)

        try {
            setForeground(createForegroundInfo(0, "Preparing to import..."))

            applicationContext.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    var importedCount = 0

                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val filename = entry.name.substringAfterLast("/")
                            val mimeType = getMimeType(filename)

                            if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
                                saveToMediaStore(zis, filename, mimeType)
                                importedCount++

                                if (importedCount % 10 == 0) {
                                    setForeground(createForegroundInfo(importedCount, "Imported $importedCount items..."))
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }

                    setForeground(createForegroundInfo(importedCount, "Import complete! $importedCount items added.", true))
                    kotlinx.coroutines.delay(2000) // Let the user see completion
                }
            }
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("ImportWorker", "Import failed", e)
            Result.failure()
        }
    }

    private fun getMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun saveToMediaStore(inputStream: InputStream, filename: String, mimeType: String) {
        val isVideo = mimeType.startsWith("video/")
        val collection = if (isVideo) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/PhotosAppImport" else "Pictures/PhotosAppImport")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = applicationContext.contentResolver
        val itemUri = resolver.insert(collection, values) ?: return

        try {
            resolver.openOutputStream(itemUri)?.use { out ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            throw e
        }
    }

    private fun createForegroundInfo(progress: Int, text: String, isComplete: Boolean = false): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "import_channel",
                "Imports",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, "import_channel")
            .setContentTitle("Importing Archive")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(!isComplete)
            .apply {
                if (!isComplete) {
                    setProgress(0, 0, true)
                }
            }
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else 0
        )
    }
}
