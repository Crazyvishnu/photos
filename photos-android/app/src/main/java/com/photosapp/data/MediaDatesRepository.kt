package com.photosapp.data

import com.photosapp.data.local.db.MediaDate
import com.photosapp.di.ServiceLocator
import com.photosapp.ui.home.LocalPhoto
import com.photosapp.worker.DateSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaDatesRepository {
    suspend fun edit(photo: LocalPhoto, seconds: Long) = withContext(Dispatchers.IO) {
        val db = ServiceLocator.db
        val uri = photo.uri.toString()
        val status = db.syncDao().getStatus(uri)
        val remote = photo.remoteKey ?: status?.driveFileId?.let { id -> status.accountEmail?.let { "drive:$it:$id" } }
        val keys = (if (photo.accountEmail != null) listOfNotNull(remote) else listOfNotNull(uri, remote)).distinct()
        val previous = keys.mapNotNull { db.mediaDateDao().get(it)?.updatedAt }.maxOrNull() ?: 0L
        val version = maxOf(System.currentTimeMillis(), previous + 1, photo.cloudDateEditedAt + 1)
        db.mediaDateDao().saveAliases(keys.map { MediaDate(it, seconds, version) })
        DateSyncWorker.enqueue(ServiceLocator.context)
    }
}
