package com.photosapp.data

import com.photosapp.data.local.db.*
import com.photosapp.di.ServiceLocator
import com.photosapp.ui.home.LocalPhoto
import com.photosapp.worker.MediaActionsWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

object MediaMutationLock { val mutex = kotlinx.coroutines.sync.Mutex() }

class MediaStateRepository {
    private val db get() = ServiceLocator.db
    suspend fun favorite(photo: LocalPhoto, value: Boolean) = withContext(Dispatchers.IO) {
        val keys = listOfNotNull(photo.uri.toString().takeIf { photo.accountEmail == null }, photo.remoteKey).distinct()
        val version = maxOf(System.currentTimeMillis(), photo.favoriteEditedAt + 1,
            (keys.mapNotNull { db.libraryStateDao().favorite(it)?.updatedAt }.maxOrNull() ?: 0) + 1)
        keys.forEach { db.libraryStateDao().saveFavorite(MediaFavorite(it, value, version)) }
        MediaActionsWorker.enqueue(ServiceLocator.context)
    }
    suspend fun moveToBin(photos: List<LocalPhoto>) = MediaMutationLock.mutex.withLock { withContext(Dispatchers.IO) {
        for (photo in photos) {
            val status = db.syncDao().getStatus(photo.uri.toString())
            val remote = photo.remoteKey ?: status?.driveFileId?.let { id -> status.accountEmail?.let { "drive:$it:$id" } }
            val parts = remote?.split(':', limit = 3)
            val email = parts?.getOrNull(1)
            val id = parts?.getOrNull(2)
            val local = (if (email != null && id != null) db.syncDao().forCloud(email, id).map { it.localUri } else emptyList()) +
                listOfNotNull(photo.uri.toString().takeIf { photo.accountEmail == null })
            val key = remote ?: photo.uri.toString()
            val entry = TrashEntry(key, photo.uri.toString(), photo.name, photo.dateAdded, photo.isVideo, email, id,
                photo.thumbnailLink, JSONArray(local.distinct()).toString(), System.currentTimeMillis(), pendingAction = if (email == null) "" else "TRASH")
            db.libraryStateDao().putTrash(entry, local + listOfNotNull(remote, photo.uri.toString()))
        }
        MediaActionsWorker.enqueue(ServiceLocator.context)
    } }
    suspend fun restore(entries: List<TrashEntry>) = MediaMutationLock.mutex.withLock { withContext(Dispatchers.IO) {
        for (requested in entries) {
            val entry = db.libraryStateDao().trash(requested.entryKey) ?: continue
            if (entry.permanent) continue
            if (entry.accountEmail == null) db.libraryStateDao().clearTrash(entry.entryKey)
            else db.libraryStateDao().saveTrash(entry.copy(pendingAction = "RESTORE", error = null))
        }
        MediaActionsWorker.enqueue(ServiceLocator.context)
    } }
    suspend fun purge(entries: List<TrashEntry>) = MediaMutationLock.mutex.withLock { withContext(Dispatchers.IO) {
        for (requested in entries) {
            val entry = db.libraryStateDao().trash(requested.entryKey) ?: continue
            localUris(entry).forEach { uri ->
                db.sharedMediaDao().get(uri)?.let { shared ->
                    val folder = java.io.File(ServiceLocator.context.filesDir, "shared_media").canonicalFile
                    val file = java.io.File(folder, shared.storageName).canonicalFile
                    check(file.parentFile == folder)
                    if (file.exists() && !file.delete()) error("Could not delete the device copy")
                    db.sharedMediaDao().delete(uri)
                }
            }
            db.libraryStateDao().saveTrash(entry.copy(permanent = true, pendingAction = "DELETE", error = null))
        }
        MediaActionsWorker.enqueue(ServiceLocator.context)
    } }
    /** Invoked only after Android has approved any public-device deletion. Cloud backup remains. */
    suspend fun removePrivateDeviceCopy(photo: LocalPhoto) = withContext(Dispatchers.IO) {
        db.sharedMediaDao().get(photo.uri.toString())?.let { shared ->
            val folder = java.io.File(ServiceLocator.context.filesDir, "shared_media").canonicalFile
            val file = java.io.File(folder, shared.storageName).canonicalFile
            check(file.parentFile == folder)
            if (file.exists() && !file.delete()) error("Could not delete the device copy")
            db.sharedMediaDao().delete(shared.uri)
        }
    }
    companion object {
        fun localUris(entry: TrashEntry): List<String> = runCatching {
            val array = JSONArray(entry.localUris)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
        fun adoptCloud(item: CloudMedia) {
            val db = ServiceLocator.db
            val dao = db.libraryStateDao()
            val key = "drive:${item.accountEmail}:${item.fileId}"
            val existing = dao.trashFor(key)
            if (item.isTrashed && existing == null) {
                val local = db.syncDao().forCloud(item.accountEmail, item.fileId).map { it.localUri }
                dao.putTrash(TrashEntry(key, local.firstOrNull() ?: "https://www.googleapis.com/drive/v3/files/${item.fileId}?alt=media",
                    item.name, item.capturedAt, item.mimeType.startsWith("video/"), item.accountEmail, item.fileId,
                    item.thumbnailLink, JSONArray(local).toString(), System.currentTimeMillis(), pendingAction = ""), local + key)
            } else if (!item.isTrashed && existing != null && !existing.permanent && existing.pendingAction.isEmpty()) {
                dao.clearTrash(existing.entryKey)
            }
        }
    }
}
