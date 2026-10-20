package com.photosapp.worker

import android.content.Context
import androidx.work.*
import com.photosapp.data.MediaMutationLock
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MediaActionsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = ServiceLocator.db
        val dao = db.libraryStateDao()
        var failed = false
        for (entry in dao.pendingTrash()) {
            try {
                com.photosapp.data.CloudLibraryRepository.remoteLock.withLock remote@ {
                val valid = MediaMutationLock.mutex.withLock { dao.trash(entry.entryKey)?.let { it.pendingAction == entry.pendingAction && it.trashedAt == entry.trashedAt && it.permanent == entry.permanent && it.fileId == entry.fileId } == true }
                if (!valid) return@remote
                val email = entry.accountEmail
                val id = entry.fileId
                if (email != null && id != null) {
                    check(db.accountDao().getAccount(email) != null) { "Reconnect the owning account to complete this action" }
                    val api = ServiceLocator.getApiForAccount(email)
                    when (entry.pendingAction) {
                        "DELETE" -> {
                            val result = api.deleteFile(id)
                            if (!result.isSuccessful && result.code() != 404) throw retrofit2.HttpException(result)
                            result.body()?.close(); result.errorBody()?.close()
                            db.cloudMediaDao().delete(email, id)
                        }
                        "RESTORE", "TRASH" -> {
                            val trashed = entry.pendingAction == "TRASH"
                            api.updateFileMetadata(id, JSONObject().put("trashed", trashed).toString().toRequestBody("application/json".toMediaType()))
                            db.cloudMediaDao().updateTrash(email, id, trashed)
                        }
                    }
                }
                MediaMutationLock.mutex.withLock {
                    val latest = dao.trash(entry.entryKey)
                    if (latest?.pendingAction == entry.pendingAction && latest.trashedAt == entry.trashedAt && latest.permanent == entry.permanent && latest.fileId == entry.fileId) {
                        if (entry.pendingAction == "RESTORE") dao.clearTrash(entry.entryKey)
                        else dao.saveTrash(latest.copy(pendingAction = "", error = null))
                    }
                }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                MediaMutationLock.mutex.withLock {
                    val latest = dao.trash(entry.entryKey)
                    if (latest?.pendingAction == entry.pendingAction) dao.saveTrash(latest.copy(error = error.message ?: "Could not complete this action"))
                }
                failed = true
            }
        }
        if (!ServiceLocator.prefs.localMode.first()) for (pending in dao.pendingFavorites()) {
            val key = if (pending.mediaKey.startsWith("drive:")) pending.mediaKey else {
                val status = db.syncDao().getStatus(pending.mediaKey)
                if (status?.driveFileId == null || status.accountEmail == null) continue
                "drive:${status.accountEmail}:${status.driveFileId}"
            }
            val parts = key.split(':', limit = 3)
            if (parts.size != 3 || db.accountDao().getAccount(parts[1]) == null) continue
            val latest = listOfNotNull(dao.favorite(key), dao.favorite(pending.mediaKey)).maxBy { it.updatedAt }
            try {
                val api = ServiceLocator.getApiForAccount(parts[1])
                val server = api.getFile(parts[2]).appProperties.orEmpty()
                val serverVersion = server["favoriteEditedAt"]?.toLongOrNull() ?: 0L
                if (serverVersion > latest.updatedAt) {
                    val value = server["favorite"] == "true"
                    listOf(key, pending.mediaKey).distinct().forEach {
                        dao.saveFavorite(com.photosapp.data.local.db.MediaFavorite(it, value, serverVersion, false))
                    }
                    db.cloudMediaDao().updateFavorite(parts[1], parts[2], value, serverVersion)
                    continue
                }
                api.updateFileMetadata(parts[2], JSONObject().put("appProperties",
                    JSONObject().put("favorite", latest.favorite.toString()).put("favoriteEditedAt", latest.updatedAt.toString()))
                    .toString().toRequestBody("application/json".toMediaType()))
                dao.favoriteSynced(pending.mediaKey, pending.updatedAt); dao.favoriteSynced(key, latest.updatedAt)
                db.cloudMediaDao().updateFavorite(parts[1], parts[2], latest.favorite, latest.updatedAt)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failed = true }
        }
        if (failed) Result.retry() else Result.success()
    }
    companion object {
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork("media_actions", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<MediaActionsWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}
