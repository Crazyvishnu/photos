package com.photosapp.worker
import androidx.room.withTransaction

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.*
import com.photosapp.data.CloudLibraryRepository
import com.photosapp.data.local.db.SyncState
import com.photosapp.data.local.db.SyncStatus
import com.photosapp.di.ServiceLocator
import com.photosapp.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import com.photosapp.data.UploadSource
import com.photosapp.data.LocalSourceException
import com.photosapp.data.SourceChangedException
import com.photosapp.data.DriveResumableUploader
import com.photosapp.data.DriveUploadFailure
import com.photosapp.data.UploadConnectionException
import java.util.concurrent.TimeUnit


class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val db = ServiceLocator.db
    private val notices by lazy { BackupNotifications(applicationContext) }

    override suspend fun doWork(): Result {
        setProgress(workDataOf(KEY_ACTIVE to false))
        return uploadLock.withLock { withContext(Dispatchers.IO) {
        var activeUri: String? = null
        try {
            if (db.accountDao().getAllAccounts().isEmpty()) return@withContext Result.failure(workDataOf(KEY_ERROR to applicationContext.getString(R.string.backup_connect_detail)))
            if (ServiceLocator.prefs.backupPaused.first()) return@withContext Result.success()
            if (ServiceLocator.prefs.localMode.first()) return@withContext Result.success()
            publish(PHASE_SCANNING)
            val wifiOnly = ServiceLocator.prefs.wifiOnly.first()
            val connectivity = applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
            if (wifiOnly && connectivity.isActiveNetworkMetered) return@withContext Result.retry()
            setForeground(notices.foreground())
            publish(PHASE_CHECKING)
            if (CloudLibraryRepository().refresh().isNotEmpty()) return@withContext Result.retry()
            val accountsForBatch = db.accountDao().getAllAccounts().sortedBy { it.email }
            val quotas = accountsForBatch.associate { it.email to ServiceLocator.getApiForAccount(it.email).getDriveAbout().storageQuota }.toMutableMap()
            quotas.forEach { (email, quota) -> db.accountDao().updateQuota(email, quota.limit, quota.usage) }
            val requested = db.syncDao().getByState(SyncState.PENDING) + db.syncDao().getByState(SyncState.FAILED) +
                db.syncDao().getByState(SyncState.UPLOADING)
            val media = linkedMapOf<String, LocalMedia>()
            requested.sortedBy { it.lastAttemptTimeMs }.forEach { status ->
                val uri = Uri.parse(status.localUri)
                if (uri.scheme == "content") {
                    try { readMedia(uri)?.let { media[uri.toString()] = it } }
                    catch (e: SecurityException) { db.syncDao().markFailed(uri.toString(), failureMessage(e)) }
                }
            }
            val allDeviceMedia = inputData.getBoolean(KEY_ALL_DEVICE_MEDIA, false)
            if (allDeviceMedia) db.sharedMediaDao().getAll().forEach { shared ->
                val status = db.syncDao().getStatus(shared.uri)
                if (status?.status != SyncState.SUCCESS || status.accountEmail?.let { db.accountDao().getAccount(it) } == null)
                    readMedia(Uri.parse(shared.uri))?.let { media[shared.uri] = it }
            }
            if (allDeviceMedia || (ServiceLocator.prefs.backupCameraRoll.first() && !inputData.getBoolean(KEY_SELECTED_ONLY, false))) {
                var accessibleCollections = 0
                listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { collection ->
                    val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        if (collection == MediaStore.Images.Media.EXTERNAL_CONTENT_URI) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_MEDIA_VIDEO
                    } else android.Manifest.permission.READ_EXTERNAL_STORAGE
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val partial = android.os.Build.VERSION.SDK_INT >= 34 && androidx.core.content.ContextCompat.checkSelfPermission(applicationContext,
                        android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted && !partial) return@forEach
                    accessibleCollections++
                    val includeAllFolders = allDeviceMedia || ServiceLocator.prefs.backupAllFolders.first()
                    val selection = if (includeAllFolders) null else "${MediaStore.MediaColumns.DATA} LIKE ?"
                    val args = if (includeAllFolders) null else arrayOf("%/DCIM/%")
                    applicationContext.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                        selection, args, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                            val status = db.syncDao().getStatus(uri.toString())
                            val ownerLinked = status?.accountEmail?.let { db.accountDao().getAccount(it) != null } == true
                            if (status?.status != SyncState.SUCCESS || !ownerLinked) {
                                readMedia(uri)?.let { media[uri.toString()] = it }
                            }
                        }
                    }
                }
                if (allDeviceMedia && accessibleCollections == 0 && media.isEmpty()) return@withContext Result.failure(
                    workDataOf(KEY_ERROR to applicationContext.getString(R.string.backup_error_permission)))
            }
            media.keys.filter { db.libraryStateDao().isHidden(it) }.forEach { media.remove(it) }
            if (media.isEmpty()) return@withContext Result.success()
            // Record the queue before processing so every discovered item has an honest waiting state.
            db.syncDao().insertAll(media.keys.mapNotNull { uri ->
                val current = db.syncDao().getStatus(uri)
                if (current?.status == SyncState.FAILED) null
                else (current ?: SyncStatus(localUri = uri)).copy(status = SyncState.PENDING, errorMessage = null)
            })
            notices.load(media.keys)
            notices.clearPrevious()
            setForeground(notices.foreground())
            var completed = 0
            var failed = false
            for (item in media.values) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (db.libraryStateDao().isHidden(item.uri.toString())) continue
                activeUri = item.uri.toString()
                val old = db.syncDao().getStatus(item.uri.toString())
                notices.begin(item.uri.toString(), item.name)
                publish(PHASE_PREPARING, item.uri.toString())
                db.syncDao().insert((old ?: SyncStatus(localUri = item.uri.toString())).copy(status = SyncState.UPLOADING, errorMessage = null))
                try {
                    val shared = db.sharedMediaDao().get(item.uri.toString())
                    val fingerprint = mediaFingerprint(item.uri)
                    val cached = db.libraryStateDao().hash(item.uri.toString())?.takeIf { fingerprint.isNotBlank() && it.fingerprint == fingerprint }
                    if (shared == null && cached != null && reuseBackup(item, cached.sha256, accountsForBatch.map { it.email }.toSet())) {
                        completed++
                        activeUri = null
                        updateCheckingNotification(completed, media.size)
                        continue
                    }
                    val source = UploadSource(applicationContext, item.uri)
                    val hash = shared?.sha256 ?: cached?.sha256 ?: source.sha256()
                    if (shared == null && fingerprint.isNotBlank()) db.libraryStateDao().saveHash(com.photosapp.data.local.db.MediaHash(item.uri.toString(), fingerprint, hash))
                    if (reuseBackup(item, hash, accountsForBatch.map { it.email }.toSet())) {
                        completed++
                        activeUri = null
                        updateCheckingNotification(completed, media.size)
                        continue
                    }
                    if (fingerprint.isNotEmpty() && mediaFingerprint(item.uri) != fingerprint) throw SourceChangedException()
                    if (db.libraryStateDao().isHidden(item.uri.toString())) continue
                    publish(PHASE_CHECKING, item.uri.toString())
                    var fileId: String? = null
                    var ownerEmail: String? = null
                    val previous = db.uploadSessionDao().get(item.uri.toString())
                    val accounts = accountsForBatch.filter { db.accountDao().getAccount(it.email) != null }
                        .sortedBy { if (it.email == previous?.accountEmail) 0 else 1 }
                    if (fileId == null) {
                        for (account in accounts) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (db.accountDao().getAccount(account.email) == null) continue
                            val quota = quotas.getValue(account.email)
                            // Keep a little space for Gmail; never route an oversized file to a full account.
                            val resuming = previous?.accountEmail == account.email && previous.sha256 == hash && previous.size == source.size
                            if (!resuming && quota.limit != null && quota.limit - quota.usage < source.size + RESERVE_BYTES) continue
                            try {
                                publish(PHASE_UPLOADING, item.uri.toString())
                                var lastSourceCheck = 0L
                                val result = DriveResumableUploader { sent, total ->
                                    notices.acknowledged(sent, total)
                                    publish(PHASE_UPLOADING, item.uri.toString(), (sent * 100 / total).toInt().coerceIn(0,100))
                                }
                                    .upload(account.email, source, hash, uploadMetadata(item, hash),
                                        if (connectivity.isActiveNetworkMetered) 256 * 1024 else 1024 * 1024,
                                        maxChunkSize = if (connectivity.isActiveNetworkMetered) 4 * 1024 * 1024 else 8 * 1024 * 1024) {
                                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        val checkTime = android.os.SystemClock.elapsedRealtime()
                                        if (checkTime - lastSourceCheck >= 2000) {
                                            lastSourceCheck = checkTime
                                            if (ServiceLocator.prefs.wifiOnly.first() && connectivity.isActiveNetworkMetered) throw UploadConnectionException(java.io.IOException("Waiting for Wi-Fi"))
                                            if (fingerprint.isNotEmpty() && mediaFingerprint(item.uri) != fingerprint) throw SourceChangedException()
                                        }
                                    }
                                fileId = result.id
                                ownerEmail = account.email
                                val usage = quota.usage + if (result.recovered) 0 else source.size
                                quotas[account.email] = quota.copy(usage = usage)
                                db.accountDao().updateQuota(account.email, quota.limit, usage)
                                db.cloudMediaDao().insertAll(listOf(com.photosapp.data.local.db.CloudMedia(account.email, requireNotNull(fileId), item.name, item.mime,
                                    item.date, source.size, hash, null, dateEditedAt = item.dateEditedAt, dateSource = item.dateSource)))
                                break
                            } catch (e: DriveUploadFailure) {
                                if (e.status != 403 || e.reason != "storageQuotaExceeded") throw e
                                db.uploadSessionDao().delete(item.uri.toString())
                                quotas[account.email] = quota.copy(usage = quota.limit ?: quota.usage)
                            }
                        }
                    }
                    if (fileId.isNullOrBlank()) throw NoBackupSpaceException()
                    recordBackup(item, requireNotNull(ownerEmail), fileId)
                    completed++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("BackupWorker", "Backup failed: ${item.name}", e)
                    if (e is SourceChangedException) {
                        db.libraryStateDao().deleteHash(item.uri.toString())
                        db.uploadSessionDao().delete(item.uri.toString())
                    }
                    db.syncDao().markFailed(item.uri.toString(), failureMessage(e))
                    notices.failed(item.uri.toString())
                    notices.update()
                    failed = true
                    if (e is UploadConnectionException) { notices.finish(true); return@withContext Result.retry() }
                }
                activeUri = null
                publish(PHASE_SCANNING)
                notices.update()
            }
            publish(PHASE_FINISHING)
            CloudLibraryRepository().refresh()
            notices.finish(failed)
            if (failed) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            activeUri?.let { db.syncDao().updateStatus(it, SyncState.PENDING) }
            notices.finish(true)
            throw e
        } catch (e: SecurityException) {
            Result.failure(workDataOf(KEY_ERROR to failureMessage(e)))
        } catch (e: Exception) {
            android.util.Log.e("BackupWorker", "Backup interrupted", e)
            notices.finish(true)
            Result.retry()
        }
        } }
    }

    private var lastNotification = 0L
    private suspend fun updateCheckingNotification(done: Int, total: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNotification >= 1000 || done == total) {
            lastNotification = now
            notices.update(force = true)
        }
    }

    private suspend fun recordBackup(item: LocalMedia, email: String, id: String) = com.photosapp.data.MediaMutationLock.mutex.withLock {
        db.syncDao().insert(SyncStatus(localUri = item.uri.toString(), status = SyncState.SUCCESS,
            driveFileId = id, accountEmail = email, lastAttemptTimeMs = System.currentTimeMillis()))
        db.uploadSessionDao().delete(item.uri.toString())
        if (db.libraryStateDao().isHidden(item.uri.toString())) notices.exclude(item.uri.toString()) else notices.done(item.uri.toString())
        notices.update()
        db.libraryStateDao().linkBackup(item.uri.toString(), email, id)
        val key = "drive:$email:$id"
        db.albumDao().linkBackup(item.uri.toString(), key)
        val affectedAlbums = db.albumDao().albumsFor(key)
        if (affectedAlbums.isNotEmpty()) {
            db.withTransaction { affectedAlbums.forEach { com.photosapp.data.AlbumSyncRepository.mark(it) } }
            AlbumSyncWorker.enqueue(applicationContext)
        }
        db.mediaDateDao().linkBackup(item.uri.toString(), key)
        if (db.mediaDateDao().get(item.uri.toString()) != null) DateSyncWorker.enqueue(applicationContext)
        if (db.libraryStateDao().isHidden(item.uri.toString()) || db.libraryStateDao().favorite(item.uri.toString()) != null) MediaActionsWorker.enqueue(applicationContext)
    }

    private suspend fun reuseBackup(item: LocalMedia, hash: String, linked: Set<String>): Boolean {
        val match = db.cloudMediaDao().findAnyHash(hash).firstOrNull { it.accountEmail in linked } ?: return false
        // A cloud Bin item must not be uploaded again just because local state was cleared.
        val key = "drive:${match.accountEmail}:${match.fileId}"
        if (match.isTrashed) com.photosapp.data.MediaStateRepository.adoptCloud(match)
        db.libraryStateDao().trashFor(key)?.let { bin ->
            val local = (com.photosapp.data.MediaStateRepository.localUris(bin) + item.uri.toString()).distinct()
            db.libraryStateDao().putTrash(bin.copy(localUris = org.json.JSONArray(local).toString()), local + key)
        }
        recordBackup(item, match.accountEmail, match.fileId)
        return true
    }

    private fun mediaFingerprint(uri: Uri): String = runCatching {
        if (uri.authority != "media" || android.os.Build.VERSION.SDK_INT < 30) return@runCatching ""
        val version = MediaStore.getVersion(applicationContext)
        applicationContext.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.GENERATION_MODIFIED), null, null, null)?.use {
            if (it.moveToFirst()) "$version:${it.getLong(0)}:${it.getLong(1)}:${it.getLong(2)}" else ""
        }.orEmpty()
    }.getOrDefault("")

    private fun uploadMetadata(item: LocalMedia, hash: String) = JSONObject().apply {
            put("name", item.name)
            put("mimeType", item.mime)
            put("appProperties", JSONObject().apply {
                put("library", CloudLibraryRepository.APP_MARKER)
                put("sha256", hash)
                put("capturedAt", item.date.toString())
                put("dateSource", item.dateSource)
                if (item.dateEditedAt > 0) put("dateEditedAt", item.dateEditedAt.toString())
                val hint = item.folder + "/" + item.name
                put("sourceAlbum", when {
                    hint.contains("screenshot", true) -> "Screenshots"
                    Regex("(?i)(document|scan|receipt)").containsMatchIn(hint) -> "Documents"
                    else -> ""
                })
            })
        }

    private fun readMedia(uri: Uri): LocalMedia? {
        val status = db.syncDao().getStatus(uri.toString())
        val remoteKey = status?.driveFileId?.let { id -> status.accountEmail?.let { "drive:$it:$id" } }
        val edit = listOfNotNull(db.mediaDateDao().get(uri.toString()), remoteKey?.let { db.mediaDateDao().get(it) }).maxByOrNull { it.updatedAt }
        val shared = db.sharedMediaDao().get(uri.toString())
        if (shared != null) return LocalMedia(uri, shared.name, shared.mimeType, edit?.capturedAt ?: shared.capturedAt,
            "Shared", if (edit != null) "edited" else shared.dateSource, edit?.updatedAt ?: 0)
        return applicationContext.contentResolver.query(uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED,
                if (android.os.Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA,
                MediaStore.Images.ImageColumns.DATE_TAKEN), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val taken = if (!cursor.isNull(4)) cursor.getLong(4).takeIf { it > 0 }?.div(1000L) else null
                LocalMedia(uri, cursor.getString(0) ?: "Photo", cursor.getString(1) ?: "application/octet-stream",
                    edit?.capturedAt ?: taken ?: cursor.getLong(2), cursor.getString(3).orEmpty(),
                    if (edit != null) "edited" else "device", edit?.updatedAt ?: 0)
            }
        }
    }

    private data class LocalMedia(val uri: Uri, val name: String, val mime: String, val date: Long, val folder: String = "",
        val dateSource: String = "device", val dateEditedAt: Long = 0)

    private var lastPublish = 0L
    private suspend fun publish(phase: String, uri: String? = null, percent: Int = 0) {
        val now = android.os.SystemClock.elapsedRealtime()
        notices.phase(phase)
        notices.update()
        if (now - lastPublish < 500) return
        lastPublish = now
        setProgress(workDataOf(KEY_ACTIVE to true, KEY_PHASE to phase, KEY_URI to uri, KEY_PERCENT to percent))
    }

    private class NoBackupSpaceException : Exception()
    private fun failureMessage(error: Exception): String {
        val causes=generateSequence<Throwable>(error) { it.cause }.take(12).toList()
        val auth=causes.any { it is com.google.android.gms.auth.GoogleAuthException }
        val diskFull=causes.any { it is android.database.sqlite.SQLiteFullException ||
            (it is android.system.ErrnoException && it.errno==android.system.OsConstants.ENOSPC) }
        val drive=error as? DriveUploadFailure
        if(drive!=null) {
            if(drive.status==0) return applicationContext.getString(R.string.backup_error_protocol,drive.reason)
            val guidance=applicationContext.getString(when {
                drive.reason=="storageQuotaExceeded" -> R.string.backup_error_space
                drive.status==401 || (drive.status==403 && drive.reason !in listOf("rateLimitExceeded","userRateLimitExceeded")) -> R.string.backup_error_account
                else -> R.string.backup_error_server
            })
            return applicationContext.getString(R.string.backup_error_drive_detail,drive.status,drive.reason,guidance)
        }
        val resource=when {
            diskFull -> R.string.backup_error_phone_space
            auth -> R.string.backup_error_account
            error is NoBackupSpaceException -> R.string.backup_error_space
            error is SecurityException -> R.string.backup_error_permission
            error is SourceChangedException -> R.string.backup_error_source_changed
            error is LocalSourceException || error is java.io.FileNotFoundException -> R.string.backup_error_local_file
            causes.any { it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException } -> R.string.backup_error_timeout
            causes.any { it is java.net.UnknownHostException } -> R.string.backup_error_dns
            causes.any { it is javax.net.ssl.SSLException } -> R.string.backup_error_tls
            error is UploadConnectionException || error is java.io.IOException -> R.string.backup_error_network_resume
            error is retrofit2.HttpException -> if(error.code()==401 || error.code()==403) R.string.backup_error_account else R.string.backup_error_server
            else -> null
        }
        return if(resource!=null) applicationContext.getString(resource)
            else applicationContext.getString(R.string.backup_internal_detail,error.javaClass.simpleName)
    }

    companion object {
        private val uploadLock = Mutex()
        const val WORK_NAME = "photo_backup"
        const val KEY_SELECTED_ONLY = "selected_only"
        const val KEY_ALL_DEVICE_MEDIA = "all_device_media"
        const val MANUAL_TAG = "manual_backup"
        const val KEY_ACTIVE = "backup_active"
        const val KEY_PHASE = "backup_phase"
        const val KEY_URI = "backup_uri"
        const val KEY_PERCENT = "backup_percent"
        const val KEY_ERROR = "error"
        const val PHASE_SCANNING = "scanning"
        const val PHASE_PREPARING = "preparing"
        const val PHASE_CHECKING = "checking"
        const val PHASE_UPLOADING = "uploading"
        const val PHASE_FINISHING = "finishing"
        private const val RESERVE_BYTES = 10L * 1024 * 1024
        fun buildRequest(wifiOnly: Boolean = false): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK_NAME).build()
    }
}
