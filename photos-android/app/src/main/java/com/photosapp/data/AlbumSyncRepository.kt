package com.photosapp.data

import androidx.room.withTransaction
import com.photosapp.data.local.db.*
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Shared, private Drive JSON manifests. Media bytes and device-only members are never copied here. */
class AlbumSyncRepository {
    private val db get() = ServiceLocator.db
    companion object {
        const val MARKER = "photosapp.albums.v1"
        private val lock = Mutex()
        fun mark(id: String, deleted: Boolean = false) {
            val dao = ServiceLocator.db.albumCloudDao()
            val old = dao.get(id)
            dao.save((old ?: AlbumCloudState(id, 0)).copy(updatedAt = maxOf(System.currentTimeMillis(), (old?.updatedAt ?: 0) + 1), pending = true, deleted = deleted))
        }
    }
    suspend fun sync(): Boolean = lock.withLock {
        var failed = false
        val accounts = db.accountDao().getAllAccounts()
        if (accounts.isEmpty()) return@withLock false
        for (account in accounts) {
            try {
                val api = ServiceLocator.getApiForAccount(account.email)
                var page: String? = null
                do {
                    val list = api.searchFiles("trashed=false and appProperties has { key='library' and value='$MARKER' }", pageToken = page)
                    for (file in list.files) {
                        require((file.size ?: 0) <= 5 * 1024 * 1024) { "Album document too large" }
                        val document = api.albumDocument(file.id).use { body ->
                            val input = body.byteStream(); val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                require(output.size() + count <= 5 * 1024 * 1024) { "Album document too large" }
                                output.write(buffer, 0, count)
                            }
                            JSONObject(String(output.toByteArray(), Charsets.UTF_8))
                        }
                        val id = document.getString("id"); val version = document.getLong("updatedAt")
                        require(id.matches(Regex("[\\w-]{1,100}")) && version >= 0)
                        val members = document.getJSONArray("members"); require(members.length() <= 100000)
                        val keys = (0 until members.length()).map { members.getString(it) }; require(keys.all { it.startsWith("drive:") })
                        db.withTransaction {
                            val old = db.albumCloudDao().get(id)
                            if (old == null || version > old.updatedAt || (version == old.updatedAt && !old.pending)) {
                                val deleted = document.optBoolean("deleted", false)
                                val localOnly = db.albumDao().members(id).filter { member ->
                                    !member.mediaKey.startsWith("drive:") && db.syncDao().getStatus(member.mediaKey)?.status != SyncState.SUCCESS
                                }
                                db.albumDao().delete(id)
                                if (!deleted) {
                                    db.albumDao().save(Album(id, document.getString("title").take(100), document.optLong("createdAt", version)))
                                    db.albumDao().add(localOnly + keys.map { AlbumMember(id, it) })
                                }
                                db.albumCloudDao().save(AlbumCloudState(id, version, false, deleted, account.email, file.id))
                            } else if (old.email == null) db.albumCloudDao().save(old.copy(email = account.email, fileId = file.id))
                        }
                    }
                    page = list.nextPageToken
                } while (page != null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("AlbumSync", "Album refresh failed", e); failed = true }
        }
        // Do not publish local snapshots over a cloud state we could not read.
        if (failed) return@withLock true
        for (pending in db.albumCloudDao().pending()) {
            try {
                val email = pending.email ?: accounts.first().email
                if (accounts.none { it.email == email }) continue
                val api = ServiceLocator.getApiForAccount(email)
                val id = pending.fileId ?: ServiceLocator.reserveDriveFileId(email).also { reserved ->
                    db.withTransaction { val current = db.albumCloudDao().get(pending.id); if (current?.updatedAt == pending.updatedAt) db.albumCloudDao().save(current.copy(email = email, fileId = reserved)) }
                }
                val snapshot = db.withTransaction {
                    val current = db.albumCloudDao().get(pending.id) ?: return@withTransaction null
                    if (current.updatedAt != pending.updatedAt) return@withTransaction null
                    val album = db.albumDao().all().find { it.id == pending.id }
                    JSONObject().put("version", 1).put("id", pending.id).put("title", album?.title ?: "Deleted album")
                        .put("createdAt", album?.createdAt ?: pending.updatedAt).put("updatedAt", pending.updatedAt)
                        .put("deleted", pending.deleted).put("members", JSONArray(db.albumDao().members(pending.id).map { it.mediaKey }.filter { it.startsWith("drive:") }.distinct()))
                } ?: continue
                val metadata = JSONObject().put("name", "Photos album - ${snapshot.getString("title")}").put("mimeType", "application/json")
                    .put("appProperties", JSONObject().put("library", MARKER).put("albumId", pending.id).put("updatedAt", pending.updatedAt.toString()))
                val boundary = "photos_${java.util.UUID.randomUUID()}"
                fun body(create: Boolean): okhttp3.RequestBody {
                    val meta = JSONObject(metadata.toString()); if (create) meta.put("id", id)
                    return ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/json\r\n\r\n$snapshot\r\n--$boundary--")
                        .toRequestBody("multipart/related; boundary=$boundary".toMediaType())
                }
                val exists = try { api.getFile(id); true } catch (e: retrofit2.HttpException) { if (e.code() == 404) false else throw e }
                if (exists) {
                    val remote = api.getFile(id)
                    if ((remote.appProperties?.get("updatedAt")?.toLongOrNull() ?: 0) > pending.updatedAt) { failed = true; continue }
                    api.writeAlbumDocument(id, body(false))
                } else try { api.createAlbumDocument(body(true)) } catch (e: retrofit2.HttpException) { if (e.code() == 409) { failed = true; continue } else throw e }
                db.withTransaction {
                    val current = db.albumCloudDao().get(pending.id)
                    if (current?.updatedAt == pending.updatedAt) db.albumCloudDao().save(current.copy(pending = false, email = email, fileId = id))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("AlbumSync", "Album upload failed", e); failed = true }
        }
        failed || db.albumCloudDao().pending().any { row -> row.email == null || accounts.any { it.email == row.email } }
    }
}
