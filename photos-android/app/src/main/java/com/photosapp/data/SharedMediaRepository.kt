package com.photosapp.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.photosapp.data.local.db.SharedMedia
import com.photosapp.data.local.db.SyncState
import com.photosapp.data.local.db.SyncStatus
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class SharedMediaRepository(private val context: Context) {
    suspend fun receive(uri: Uri, receivedAt: Long): SharedMedia = importLock.withLock {
        require(uri.scheme == "content") { "Only shared photo and video files are supported" }
        val resolver = context.contentResolver
        var name = "Shared media"
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
                }
            }
        }
        name = name.substringAfterLast('/').substringAfterLast('\\').take(240)
        val mime = resolver.getType(uri)?.lowercase()?.takeIf { it.startsWith("image/") || it.startsWith("video/") }
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        require(mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) { "Only photos and videos can be imported" }
        val folder = File(context.filesDir, "shared_media").apply { mkdirs() }
        // No other receiver can be copying while this mutex is held. Recover abandoned partial files after process death.
        folder.listFiles()?.filter { it.name.startsWith("receive_") && it.name.endsWith(".part") }?.forEach { it.delete() }
        if ((size ?: 0) > folder.usableSpace - RESERVE) throw IOException("Not enough space to receive this file")
        val temp = File.createTempFile("receive_", ".part", folder)
        var committed: File? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var chunks = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (++chunks % 128 == 0 && folder.usableSpace < RESERVE) throw IOException("Not enough space to receive this file")
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("The shared file is unavailable. Share it again from Google Photos")
            require(temp.length() > 0) { "The shared file is empty" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val dao = ServiceLocator.db.sharedMediaDao()
            val existing = dao.getByHash(hash)
            existing?.let {
                if (File(folder, existing.storageName).isFile) return@withLock existing
            }
            val capture = MediaCaptureTime.fromSharedFile(context, uri, temp, mime, receivedAt)
            val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it.matches(Regex("[a-zA-Z0-9]+")) } ?: "media"
            val saved = File(folder, existing?.storageName ?: "$hash.$extension")
            if (!temp.renameTo(saved)) throw IOException("Could not store the shared file")
            committed = saved
            val ownUri = FileProvider.getUriForFile(context, "${context.packageName}.media", saved)
            val item = existing ?: SharedMedia(ownUri.toString(), saved.name, name, mime, capture.seconds, capture.source, hash)
            dao.insert(item)
            val stored = requireNotNull(dao.getByHash(hash))
            committed = null
            stored
        } finally {
            temp.delete()
            committed?.delete()
        }
    }

    fun queue(item: SharedMedia) {
        val dao = ServiceLocator.db.syncDao()
        val old = dao.getStatus(item.uri)
        val connected = old?.accountEmail?.let { ServiceLocator.db.accountDao().getAccount(it) != null } == true
        if (old?.status != SyncState.SUCCESS || !connected) {
            dao.insert((old ?: SyncStatus(item.uri)).copy(status = SyncState.PENDING, errorMessage = null))
        }
    }
    companion object {
        private val importLock = Mutex()
        private const val RESERVE = 16L * 1024 * 1024
    }
}
