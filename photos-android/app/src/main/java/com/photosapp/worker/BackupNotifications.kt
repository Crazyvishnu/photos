package com.photosapp.worker

import android.Manifest
import android.app.NotificationManager
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.photosapp.R
import com.photosapp.di.ServiceLocator
import com.photosapp.data.local.db.SyncState

/** A device-file count snapshot plus acknowledged upload progress. Runs on the worker's IO dispatcher. */
class BackupNotifications(private val context: Context) {
    private val manager=context.getSystemService(NotificationManager::class.java)
    private val states=linkedMapOf<String,SyncState>()
    private var lastUpdate=0L
    private var name: String?=null
    private var phase=""
    private var bytes=0L
    private var totalBytes=0L
    private var lastBytes=0L
    private var lastRateAt=0L
    private var speed=0.0

    fun load(extraUris: Collection<String>) {
        val db=ServiceLocator.db
        val linked=db.accountDao().getAllAccounts().map { it.email }.toSet()
        val statuses=db.syncDao().getAll().groupBy { key(it.localUri) }.mapValues { (_,rows)-> rows.maxBy { it.lastAttemptTimeMs } }
        val hidden=db.libraryStateDao().allTrashKeys().toSet()
        fun add(uri: String) {
            val status=statuses[key(uri)]
            val remote=status?.driveFileId?.let { id -> status.accountEmail?.let { "drive:$it:$id" } }
            if(uri in hidden || status?.localUri in hidden || remote in hidden) return
            states[key(uri)]=if(status?.status==SyncState.SUCCESS && status.accountEmail in linked && status.driveFileId!=null) SyncState.SUCCESS
                else status?.status ?: SyncState.IDLE
        }
        runCatching {
            context.contentResolver.query(MediaStore.Files.getContentUri("external"),arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?)",
                arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()),null)?.use { cursor ->
                while(cursor.moveToNext()) add(android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"),cursor.getLong(0)).toString())
            }
        }
        db.sharedMediaDao().getAll().forEach { add(it.uri) }
        extraUris.forEach { add(it) }
        manager.createNotificationChannel(NotificationChannel(CHANNEL,context.getString(R.string.backup_notification_channel),NotificationManager.IMPORTANCE_LOW))
    }

    fun begin(uri: String, filename: String) {
        name=filename; states[key(uri)]=SyncState.UPLOADING
        bytes=0; totalBytes=0; lastBytes=0; lastRateAt=0; speed=0.0
    }
    fun done(uri: String) { states[key(uri)]=SyncState.SUCCESS; clearActive() }
    fun exclude(uri: String) { states.remove(key(uri)); clearActive() }
    fun failed(uri: String) { states[key(uri)]=SyncState.FAILED; clearActive() }
    private fun clearActive() { name=null; bytes=0; totalBytes=0; speed=0.0 }
    fun acknowledged(sent: Long,total: Long) {
        val now=SystemClock.elapsedRealtime()
        if(lastRateAt==0L || sent<lastBytes) { lastBytes=sent; lastRateAt=now }
        else if(now-lastRateAt>=500) {
            val rate=(sent-lastBytes)*1000.0/(now-lastRateAt)
            speed=if(speed==0.0) rate else speed*0.6+rate*0.4
            lastBytes=sent; lastRateAt=now
        }
        bytes=sent; totalBytes=total
    }
    fun phase(value: String) { phase=value }
    private fun builder(title: String, ongoing: Boolean): NotificationCompat.Builder {
        val finished=states.values.count { it==SyncState.SUCCESS }
        val failed=states.values.count { it==SyncState.FAILED }
        val remaining=states.size-finished
        val counts=context.getString(R.string.backup_notification_counts,finished,remaining)
        val transfer=if(totalBytes>0) {
            val format={ value: Long -> android.text.format.Formatter.formatShortFileSize(context,value) }
            val percent=(bytes*100/totalBytes).toInt().coerceIn(0,100)
            context.getString(R.string.backup_notification_transfer,percent,format(bytes),format(totalBytes))+
                if(speed>0) " - "+format(speed.toLong())+"/s" else ""
        } else ""
        val phaseText=context.getString(when(phase) {
            BackupWorker.PHASE_UPLOADING -> R.string.backup_status_active
            BackupWorker.PHASE_PREPARING -> R.string.backup_preparing
            BackupWorker.PHASE_CHECKING -> R.string.backup_checking_duplicates
            else -> R.string.backup_checking_library
        })
        val detail=listOf(counts,context.getString(R.string.backup_notification_failed,failed),name,transfer,
            if(ongoing) phaseText else null).filterNotNull().filter { it.isNotBlank() }.joinToString("\n")
        val intent=Intent(context,com.photosapp.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_backup_upload)
            .setContentTitle(title).setContentText(counts).setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setSubText(if(transfer.isNotEmpty()) transfer else context.getString(R.string.backup_original_quality))
            .setContentIntent(pending).setOngoing(ongoing).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(states.size,finished,states.isEmpty() && ongoing)
    }
    fun foreground(): ForegroundInfo {
        manager.createNotificationChannel(NotificationChannel(CHANNEL,context.getString(R.string.backup_notification_channel),NotificationManager.IMPORTANCE_LOW))
        val notification=builder(context.getString(R.string.backup_status_active),true).build()
        return if(Build.VERSION.SDK_INT>=29) ForegroundInfo(101,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(101,notification)
    }
    fun update(force: Boolean=false) {
        val now=SystemClock.elapsedRealtime()
        if(!force && now-lastUpdate<1500) return
        lastUpdate=now
        if(allowed()) runCatching { manager.notify(101,foreground().notification) }
    }
    fun finish(retry: Boolean) {
        if(states.isEmpty() || !allowed()) return
        val remaining=states.values.count { it!=SyncState.SUCCESS }
        val title=context.getString(if(retry) R.string.backup_notification_retry else if(remaining==0) R.string.backup_complete else R.string.backup_notification_finished)
        runCatching { manager.notify(102,builder(title,false).setAutoCancel(true).build()) }
    }
    fun clearPrevious() { manager.cancel(102) }
    private fun allowed()=Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    companion object {
        private const val CHANNEL="backup_channel"
        private fun key(uri: String): String {
            val parsed=Uri.parse(uri)
            return if(parsed.authority=="media") "media:${parsed.pathSegments.firstOrNull()}:${parsed.lastPathSegment}" else uri
        }
    }
}
