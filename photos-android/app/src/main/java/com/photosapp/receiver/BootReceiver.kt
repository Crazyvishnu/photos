package com.photosapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.photosapp.worker.BackupScheduler
import kotlinx.coroutines.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { BackupScheduler.ensure(context.applicationContext) }
            catch (e: Exception) { android.util.Log.e("BackupSchedule", "Unable to restore backup scheduling", e) }
            finally { pending.finish() }
        }
    }
}
