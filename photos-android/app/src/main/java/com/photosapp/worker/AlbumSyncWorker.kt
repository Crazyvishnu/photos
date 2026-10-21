package com.photosapp.worker

import android.content.Context
import androidx.work.*
import com.photosapp.data.AlbumSyncRepository
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class AlbumSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (ServiceLocator.prefs.localMode.first()) return@withContext Result.success()
        if (AlbumSyncRepository().sync()) Result.retry() else Result.success()
    }
    companion object {
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork("album_cloud_sync", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AlbumSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}
