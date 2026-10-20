package com.photosapp.ui.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.WorkInfo
import com.photosapp.R
import com.photosapp.data.local.db.SyncState
import com.photosapp.worker.BackupWorker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

data class BackupNetwork(val connected: Boolean, val metered: Boolean)

fun observeBackupNetwork(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    fun update() {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        trySend(BackupNetwork(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            manager.isActiveNetworkMetered))
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update()
        override fun onLost(network: Network) = update()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
    }
    manager.registerDefaultNetworkCallback(callback)
    update()
    awaitClose { manager.unregisterNetworkCallback(callback) }
}

data class BackupRuntime(
    val work: List<WorkInfo> = emptyList(),
    val paused: Boolean = false,
    val wifiOnly: Boolean = false,
    val network: BackupNetwork = BackupNetwork(false, true)
) {
    val active: WorkInfo? get() = work.firstOrNull {
        it.state == WorkInfo.State.RUNNING && it.progress.getBoolean(BackupWorker.KEY_ACTIVE, false)
    }
    val queued: Boolean get() = work.any { BackupWorker.MANUAL_TAG in it.tags && !it.state.isFinished && it.state != WorkInfo.State.RUNNING }
    val problem: String? get() = work.lastOrNull { it.state == WorkInfo.State.FAILED }
        ?.outputData?.getString(BackupWorker.KEY_ERROR)
    val running: Boolean get() = active != null
}

data class BackupSummary(
    val backedUp: Int = 0, val uploading: Int = 0, val waiting: Int = 0,
    val notBackedUp: Int = 0, val failed: Int = 0,
    val loaded: Boolean = false, val hasAccounts: Boolean = false,
    val runtime: BackupRuntime = BackupRuntime(), val currentName: String? = null,
    val currentProgress: Int? = null
) {
    val remaining get() = uploading + waiting + notBackedUp + failed
    val total get() = backedUp + remaining

    fun title(context: Context): String = context.getString(when {
        !loaded -> R.string.backup_checking_library
        !hasAccounts -> R.string.backup_connect_account
        runtime.paused -> R.string.backup_paused
        !runtime.network.connected && (remaining > 0 || runtime.running) -> R.string.backup_waiting_connection
        runtime.wifiOnly && runtime.network.metered && (remaining > 0 || runtime.running) -> R.string.backup_waiting_wifi
        runtime.running -> when (runtime.active?.progress?.getString(BackupWorker.KEY_PHASE)) {
            BackupWorker.PHASE_UPLOADING -> R.string.backup_status_active
            BackupWorker.PHASE_CHECKING -> R.string.backup_checking_duplicates
            BackupWorker.PHASE_PREPARING -> R.string.backup_preparing
            else -> R.string.backup_checking_library
        }
        failed > 0 -> R.string.backup_needs_attention
        runtime.problem != null && remaining > 0 -> R.string.backup_needs_attention
        waiting > 0 || (runtime.queued && remaining > 0) -> R.string.backup_waiting
        remaining > 0 -> R.string.backup_not_done
        total == 0 -> R.string.backup_no_device_media
        else -> R.string.backup_complete
    })

    fun detail(context: Context): String {
        if (!hasAccounts) return context.getString(R.string.backup_connect_detail)
        val counts = context.getString(R.string.backup_counts, backedUp, remaining)
        val current = currentName?.let { name ->
            if (currentProgress != null) context.getString(R.string.backup_current_progress, name, currentProgress)
            else name
        }
        val error = if (!runtime.running && remaining > 0) runtime.problem else null
        return listOfNotNull(counts, current, error).joinToString("\n")
    }
}

fun LocalPhoto.backupLabel(context: Context): String = when (syncState) {
    SyncState.SUCCESS -> context.getString(R.string.backup_backed_up)
    SyncState.IDLE -> context.getString(R.string.backup_not_done)
    SyncState.PENDING -> context.getString(R.string.backup_waiting)
    SyncState.FAILED -> context.getString(R.string.backup_failed)
    SyncState.UPLOADING -> when (backupPhase) {
        BackupWorker.PHASE_UPLOADING -> context.getString(R.string.backup_percent, uploadProgress ?: 0)
        BackupWorker.PHASE_CHECKING -> context.getString(R.string.backup_checking)
        else -> context.getString(R.string.backup_preparing_short)
    }
}

fun LocalPhoto.backupIcon(): Int = when (syncState) {
    SyncState.SUCCESS -> R.drawable.ic_backup_done
    SyncState.IDLE -> R.drawable.ic_backup_off
    SyncState.PENDING -> R.drawable.ic_backup_waiting
    SyncState.FAILED -> R.drawable.ic_backup_error
    SyncState.UPLOADING -> R.drawable.ic_backup_upload
}

fun LocalPhoto.backupTint(): Int = when (syncState) {
    SyncState.SUCCESS -> R.color.backup_success
    SyncState.UPLOADING -> R.color.accent
    SyncState.PENDING -> R.color.backup_waiting
    SyncState.IDLE -> R.color.text_secondary
    SyncState.FAILED -> R.color.error
}
