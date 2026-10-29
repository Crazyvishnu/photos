package com.photosapp.worker

import android.content.Context
import androidx.work.*
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Discovery must schedule work without cancelling an existing upload. */
object BackupScheduler {
    private val scheduling = Mutex()
    const val AUTOMATIC = "photo_backup_automatic"
    const val WATCH = "photo_backup_media_watch"
    suspend fun ensure(context: Context) = withContext(Dispatchers.IO) { scheduling.withLock {
        val prefs = ServiceLocator.prefs
        if (prefs.backupPaused.first() || prefs.localMode.first()) return@withLock
        val wifi = prefs.wifiOnly.first()
        val manager = WorkManager.getInstance(context)
        watch(context)
        manager.enqueueUniquePeriodicWork(BackupWorker.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            BackupWorker.buildRequest(wifi))
        // At most one waiting successor is needed: it scans all current media.
        // A running upload can finish without being replaced by discovery bursts.
        val existing = manager.getWorkInfosForUniqueWork(AUTOMATIC).get(10, TimeUnit.SECONDS)
        if (existing.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return@withLock
        manager.enqueueUniqueWork(AUTOMATIC, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(
                    if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            .result.get(10, TimeUnit.SECONDS)
    } }

    fun watch(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        WorkManager.getInstance(context).enqueueUniqueWork(WATCH, policy,
            OneTimeWorkRequestBuilder<MediaChangedWorker>().setConstraints(Constraints.Builder()
                .addContentUriTrigger(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(2, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(10, TimeUnit.SECONDS).build()).build())
    }
}

class MediaChangedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            BackupScheduler.ensure(applicationContext)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("BackupSchedule", "Media event scheduling failed", e)
            Result.retry()
        } finally {
            BackupScheduler.watch(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }
}
