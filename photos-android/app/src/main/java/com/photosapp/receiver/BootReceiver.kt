package com.photosapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.photosapp.worker.BackupWorker

/**
 * Restarts the periodic backup work after device reboot.
 * WorkManager usually survives reboot on API 23+, but this is extra safety.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                BackupWorker.WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                BackupWorker.buildRequest()
            )
        }
    }
}
