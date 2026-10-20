package com.photosapp.data.local.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cloud_cursors")
data class CloudCursor(@PrimaryKey val email: String, val token: String)
@Entity(tableName = "media_hashes")
data class MediaHash(@PrimaryKey val uri: String, val fingerprint: String, val sha256: String)
@Entity(tableName = "media_favorites")
data class MediaFavorite(@PrimaryKey val mediaKey: String, val favorite: Boolean, val updatedAt: Long, val pending: Boolean = true)
@Entity(tableName = "trash_entries")
data class TrashEntry(@PrimaryKey val entryKey: String, val uri: String, val name: String, val capturedAt: Long,
    val isVideo: Boolean, val accountEmail: String?, val fileId: String?, val thumbnailLink: String?, val localUris: String,
    val trashedAt: Long, val permanent: Boolean = false, val pendingAction: String = "TRASH", val error: String? = null)
@Entity(tableName = "trash_aliases")
data class TrashAlias(@PrimaryKey val mediaKey: String, val entryKey: String)

@Dao
abstract class LibraryStateDao {
    @Query("SELECT mediaKey FROM trash_aliases") abstract fun allTrashKeys(): List<String>
    @Query("SELECT * FROM cloud_cursors WHERE email = :email") abstract fun cursor(email: String): CloudCursor?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun saveCursor(cursor: CloudCursor)
    @Query("DELETE FROM cloud_cursors WHERE email = :email") abstract fun deleteCursor(email: String)
    @Query("SELECT * FROM media_hashes WHERE uri = :uri") abstract fun hash(uri: String): MediaHash?
    @Query("DELETE FROM media_hashes WHERE uri = :uri") abstract fun deleteHash(uri: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun saveHash(hash: MediaHash)
    @Query("SELECT * FROM media_favorites") abstract fun observeFavorites(): Flow<List<MediaFavorite>>
    @Query("SELECT * FROM media_favorites WHERE pending = 1") abstract fun pendingFavorites(): List<MediaFavorite>
    @Query("SELECT * FROM media_favorites WHERE mediaKey = :key") abstract fun favorite(key: String): MediaFavorite?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun saveFavorite(favorite: MediaFavorite)
    @Query("UPDATE media_favorites SET pending = 0 WHERE mediaKey = :key AND updatedAt = :version") abstract fun favoriteSynced(key: String, version: Long)
    @Query("SELECT * FROM trash_aliases") abstract fun observeTrashAliases(): Flow<List<TrashAlias>>
    @Query("SELECT * FROM trash_entries WHERE permanent = 0 OR pendingAction != '' ORDER BY trashedAt DESC") abstract fun observeTrash(): Flow<List<TrashEntry>>
    @Query("SELECT * FROM trash_entries WHERE pendingAction != ''") abstract fun pendingTrash(): List<TrashEntry>
    @Query("SELECT * FROM trash_entries WHERE entryKey = :key") abstract fun trash(key: String): TrashEntry?
    @Query("SELECT t.* FROM trash_entries t INNER JOIN trash_aliases a ON a.entryKey = t.entryKey WHERE a.mediaKey = :key LIMIT 1") abstract fun trashFor(key: String): TrashEntry?
    @Query("SELECT EXISTS(SELECT 1 FROM trash_aliases WHERE mediaKey = :key)") abstract fun isHidden(key: String): Boolean
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun saveTrash(entry: TrashEntry)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun saveAliases(aliases: List<TrashAlias>)
    @Query("DELETE FROM trash_entries WHERE entryKey = :key") abstract fun deleteTrashRow(key: String)
    @Query("DELETE FROM trash_aliases WHERE entryKey = :key") abstract fun deleteAliases(key: String)
    @Transaction open fun clearTrash(key: String) { deleteAliases(key); deleteTrashRow(key) }
    @Transaction open fun putTrash(entry: TrashEntry, aliases: List<String>) {
        saveTrash(entry); saveAliases((aliases + entry.entryKey).distinct().map { TrashAlias(it, entry.entryKey) })
    }
    @Transaction open fun linkBackup(uri: String, email: String, id: String) {
        val remoteKey = "drive:$email:$id"
        trashFor(uri)?.let { entry ->
            saveTrash(entry.copy(accountEmail = email, fileId = id, pendingAction = if (entry.permanent) "DELETE" else "TRASH", error = null))
            saveAliases(listOf(TrashAlias(remoteKey, entry.entryKey)))
        }
        favorite(uri)?.let { local ->
            val remote = favorite(remoteKey)
            if (remote == null || remote.updatedAt < local.updatedAt) saveFavorite(local.copy(mediaKey = remoteKey, pending = true))
        }
    }
}
