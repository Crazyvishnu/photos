package com.photosapp.data
import android.net.Uri
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.photosapp.di.ServiceLocator
import com.photosapp.ui.home.LocalPhoto
import kotlinx.coroutines.*
import java.io.File
class MediaTransferRepository {
    suspend fun export(photo: LocalPhoto): Uri = withContext(Dispatchers.IO) {
        val context=ServiceLocator.context
        val folder=File(context.cacheDir,"exports").apply { mkdirs() }
        folder.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 }?.forEach { it.delete() }
        val safeName=photo.name.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"),"_")
        val file=File(folder, java.util.UUID.randomUUID().toString()+"_"+safeName)
        try {
            if(photo.accountEmail!=null) {
                val request=okhttp3.Request.Builder().url(photo.uri.toString()).header("Authorization","Bearer "+ServiceLocator.tokenForAccount(photo.accountEmail)).build()
                okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Download failed" }
                    requireNotNull(response.body).byteStream().use { input -> file.outputStream().use { output -> input.copyTo(output,128*1024) } }
                }
            } else context.contentResolver.openInputStream(photo.uri)?.use { input -> file.outputStream().use { input.copyTo(it,128*1024) } } ?: error("File unavailable")
            FileProvider.getUriForFile(context,context.packageName+".media",file)
        } catch(e: Exception) { file.delete(); throw e }
    }
    suspend fun saveBitmap(bitmap: Bitmap, photo: LocalPhoto): Uri = withContext(Dispatchers.IO) {
        val context=ServiceLocator.context
        val folder=File(context.cacheDir,"exports").apply { mkdirs() }
        val file=File(folder,"Edited_"+System.currentTimeMillis()+".jpg")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) }
        val uri=FileProvider.getUriForFile(context,context.packageName+".media",file)
        try { importEdited(uri, photo.dateAdded) } finally { file.delete() }
    }
    suspend fun importEdited(uri: Uri, capturedAt: Long): Uri = withContext(Dispatchers.IO) {
        val context=ServiceLocator.context
        val item=SharedMediaRepository(context).receive(uri,System.currentTimeMillis())
        ServiceLocator.db.mediaDateDao().saveLatest(com.photosapp.data.local.db.MediaDate(item.uri,capturedAt,System.currentTimeMillis()))
        SharedMediaRepository(context).queue(item)
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork("photo_backup_immediate",androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<com.photosapp.worker.BackupWorker>().setInputData(androidx.work.workDataOf(com.photosapp.worker.BackupWorker.KEY_SELECTED_ONLY to true)).setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build())
        Uri.parse(item.uri)
    }
}
