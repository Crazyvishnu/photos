package com.photosapp.data

import com.photosapp.data.api.DriveFile
import com.photosapp.data.local.db.*
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

class CloudLibraryRepository {
    suspend fun refresh(): List<String> = remoteLock.withLock {
        val errors = mutableListOf<String>()
        val db = ServiceLocator.db
        for (account in db.accountDao().getAllAccounts()) {
            try {
                val api = ServiceLocator.getApiForAccount(account.email)
                var cursor = db.libraryStateDao().cursor(account.email)?.token
                if (cursor == null) {
                    val scanStarted = System.currentTimeMillis()
                    val start = api.startPageToken().startPageToken
                    val items = mutableListOf<CloudMedia>()
                    var page: String? = null
                    do {
                        val response = api.searchFiles(ALL_QUERY, pageToken = page)
                        items += response.files.filter { isMedia(it) }.map { it.toCloudMedia(account.email) }
                        page = response.nextPageToken
                    } while (page != null)
                    if (db.accountDao().getAccount(account.email) == null) continue
                    db.cloudMediaDao().replaceAccount(account.email, items)
                    val present = items.map { it.fileId }.toSet()
                    db.syncDao().getByState(SyncState.SUCCESS).filter { it.accountEmail == account.email && it.driveFileId !in present }.forEach { status ->
                        status.driveFileId?.let { db.syncDao().invalidateMissingBackup(status.localUri, it, scanStarted) }
                    }
                    items.forEach { MediaStateRepository.adoptCloud(it) }
                    cursor = start
                    db.libraryStateDao().saveCursor(CloudCursor(account.email, start))
                }
                do {
                    val response = api.changes(requireNotNull(cursor))
                    if (db.accountDao().getAccount(account.email) == null) break
                    for (change in response.changes) {
                        val file = change.file
                        if (change.removed || (file != null && (file.appProperties?.get("library") != APP_MARKER || !isMedia(file)))) {
                            db.cloudMediaDao().delete(account.email, change.fileId)
                            db.syncDao().forCloud(account.email, change.fileId).forEach {
                                db.syncDao().invalidateMissingBackup(it.localUri, change.fileId, System.currentTimeMillis())
                            }
                        } else if (file != null) {
                            val item = file.toCloudMedia(account.email)
                            db.cloudMediaDao().insertAll(listOf(item)); MediaStateRepository.adoptCloud(item)
                        }
                    }
                    cursor = response.nextPageToken ?: response.newStartPageToken ?: cursor
                    db.libraryStateDao().saveCursor(CloudCursor(account.email, requireNotNull(cursor)))
                } while (response.nextPageToken != null)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (error is retrofit2.HttpException && error.code() == 410) db.libraryStateDao().deleteCursor(account.email)
                errors += account.email
            }
        }
        errors
    }
    companion object {
        internal val remoteLock = Mutex()
        const val APP_MARKER = "photosapp.library.v1"
        const val ALL_QUERY = "appProperties has { key='library' and value='$APP_MARKER' }"
        const val LIBRARY_QUERY = "trashed=false and $ALL_QUERY"
        private fun isMedia(file: DriveFile) = file.mimeType?.let { it.startsWith("image/") || it.startsWith("video/") } == true
        fun DriveFile.toCloudMedia(email: String) = CloudMedia(email, id, name, mimeType ?: "application/octet-stream",
            appProperties?.get("capturedAt")?.toLongOrNull() ?: runCatching { Instant.parse(createdTime).epochSecond }.getOrDefault(0),
            size ?: 0, appProperties?.get("sha256"), thumbnailLink, appProperties?.get("sourceAlbum").orEmpty(),
            appProperties?.get("dateEditedAt")?.toLongOrNull() ?: 0, appProperties?.get("dateSource").orEmpty(),
            trashed, appProperties?.get("favorite") == "true", appProperties?.get("favoriteEditedAt")?.toLongOrNull() ?: 0)
    }
}
