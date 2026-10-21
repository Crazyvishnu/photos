package com.photosapp.ui.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.photosapp.data.SharedMediaRepository
import com.photosapp.di.ServiceLocator
import com.photosapp.worker.BackupWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

data class ShareImportState(val started: Boolean = false, val total: Int = 0, val received: Int = 0, val failed: Int = 0,
    val complete: Boolean = false, val needsAccount: Boolean = false, val queueFailed: Boolean = false)

class ShareImportViewModel : ViewModel() {
    private val mutable = MutableStateFlow(ShareImportState())
    val state = mutable.asStateFlow()
    private val batches = Channel<List<Uri>>(Channel.UNLIMITED)
    private val pending = AtomicInteger()
    fun receive(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pending.incrementAndGet()
        mutable.update { it.copy(started = true, complete = false, total = it.total + uris.size) }
        batches.trySend(uris)
    }
    init {
        viewModelScope.launch(Dispatchers.IO) {
          for (uris in batches) {
            try {
                val context = ServiceLocator.context
                val repository = SharedMediaRepository(context)
                val receivedAt = System.currentTimeMillis()
                var received = 0
                var failed = 0
                for (uri in uris) {
                    try {
                        val item = repository.receive(uri, receivedAt)
                        repository.queue(item)
                        received++

            } catch (error: CancellationException) { throw error }
                catch (_: Exception) { failed++; mutable.update { it.copy(failed = it.failed + 1) }; continue }
                mutable.update { it.copy(received = it.received + 1) }
            }
            val needsAccount = ServiceLocator.db.accountDao().getAllAccounts().isEmpty()
            if (received > 0) {
                ServiceLocator.prefs.setLocalMode(needsAccount)
                if (!needsAccount) ServiceLocator.prefs.setBackupPaused(false)
            }
            if (received > 0 && !needsAccount) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(BackupWorker.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
                    BackupWorker.buildRequest(ServiceLocator.prefs.wifiOnly.first()))
                WorkManager.getInstance(context).enqueueUniqueWork("photo_backup_immediate", ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<BackupWorker>().addTag(BackupWorker.MANUAL_TAG)
                        .setInputData(workDataOf(BackupWorker.KEY_SELECTED_ONLY to true))
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            }
            mutable.update { it.copy(needsAccount = needsAccount) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                mutable.update { it.copy(queueFailed = true) }
            } finally {
                pending.decrementAndGet()
                mutable.update { it.copy(complete = pending.get() == 0) }
            }
          }
        }
    }
}
