package com.photosapp.worker

import android.content.Context
import androidx.work.*
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Date corrections change only Drive metadata, never re-upload the media bytes. */
class DateSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (ServiceLocator.prefs.localMode.first()) return@withContext Result.success()
        val db = ServiceLocator.db
        var failed = false
        for (pending in db.mediaDateDao().pending()) {
            val remoteKey = if (pending.mediaKey.startsWith("drive:")) pending.mediaKey else {
                val status = db.syncDao().getStatus(pending.mediaKey)
                if (status?.driveFileId == null || status.accountEmail == null || status.status != com.photosapp.data.local.db.SyncState.SUCCESS) continue
                "drive:${status.accountEmail}:${status.driveFileId}"
            }
            val parts = remoteKey.split(':', limit = 3)
            if (parts.size != 3) continue
            // Keep a durable retry when the owner temporarily needs reconnecting.
            if (db.accountDao().getAccount(parts[1]) == null) { failed = true; continue }
            // An edit may have happened while this work was queued. Always send the newest alias.
            val latest = listOfNotNull(db.mediaDateDao().get(remoteKey), db.mediaDateDao().get(pending.mediaKey)).maxBy { it.updatedAt }
            try {
                val api = ServiceLocator.getApiForAccount(parts[1])
                val file = api.getFile(parts[2])
                val serverVersion = file.appProperties?.get("dateEditedAt")?.toLongOrNull() ?: 0L
                val serverDate = file.appProperties?.get("capturedAt")?.toLongOrNull()
                if (serverVersion > latest.updatedAt && serverDate != null) {
                    db.mediaDateDao().saveAliases(listOf(remoteKey, pending.mediaKey).distinct().map {
                        com.photosapp.data.local.db.MediaDate(it, serverDate, serverVersion, false)
                    })
                    db.cloudMediaDao().updateDate(parts[1], parts[2], serverDate, serverVersion)
                    continue
                }
                if (serverVersion != latest.updatedAt || serverDate != latest.capturedAt) {
                    val properties = mutableMapOf<String, String>().apply {
                        put("capturedAt", latest.capturedAt.toString()); put("dateEditedAt", latest.updatedAt.toString()); put("dateSource", "edited")
                    }
                    val saved = api.updateFileMetadata(parts[2], JSONObject().put("appProperties", JSONObject(properties)).toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType()))
                    check(saved.appProperties?.get("capturedAt")?.toLongOrNull() == latest.capturedAt &&
                        saved.appProperties?.get("dateEditedAt")?.toLongOrNull() == latest.updatedAt) {
                        "Drive has not confirmed the edited date yet"
                    }
                }
                db.cloudMediaDao().updateDate(parts[1], parts[2], latest.capturedAt, latest.updatedAt)
                db.mediaDateDao().markSynced(pending.mediaKey, pending.updatedAt)
                db.mediaDateDao().markSynced(remoteKey, latest.updatedAt)
            } catch (error: CancellationException) { throw error }
            // Missing access/file is not an acknowledgement of the date edit.
            // Leave the journal pending so reconnect/retry can recover it.
            catch (_: retrofit2.HttpException) { failed = true }
            catch (_: Exception) { failed = true }
        }
        if (failed) Result.retry() else Result.success()
    }
    companion object {
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork("photo_date_sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<DateSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}
