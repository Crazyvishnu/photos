package com.photosapp.data.local.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "albums")
data class Album(@PrimaryKey val id: String, val title: String, val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "album_members", primaryKeys = ["albumId", "mediaKey"])
data class AlbumMember(val albumId: String, val mediaKey: String)

@Dao
abstract class AlbumDao {
    @Query("SELECT * FROM albums") abstract fun all(): List<Album>
    @Query("SELECT * FROM album_members WHERE albumId = :id") abstract fun members(id: String): List<AlbumMember>
    @Query("SELECT DISTINCT albumId FROM album_members WHERE mediaKey = :key") abstract fun albumsFor(key: String): List<String>
    @Query("SELECT * FROM albums ORDER BY createdAt DESC")
    abstract fun observeAlbums(): Flow<List<Album>>
    @Query("SELECT * FROM album_members")
    abstract fun observeMembers(): Flow<List<AlbumMember>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun save(album: Album)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun add(members: List<AlbumMember>)
    @Query("DELETE FROM albums WHERE id = :id")
    abstract fun deleteAlbum(id: String)
    @Query("DELETE FROM album_members WHERE albumId = :id")
    abstract fun deleteMembers(id: String)
    @Query("DELETE FROM album_members WHERE albumId = :id AND mediaKey IN (:keys)")
    abstract fun remove(id: String, keys: List<String>)
    @Transaction
    open fun delete(id: String) { deleteMembers(id); deleteAlbum(id) }
    // Retain the device alias as well, so unlinking its cloud account keeps local album items visible.
    @Query("INSERT OR IGNORE INTO album_members(albumId, mediaKey) SELECT albumId, :remoteKey FROM album_members WHERE mediaKey = :localKey")
    abstract fun copyIdentity(localKey: String, remoteKey: String)
    @Transaction
    open fun linkBackup(localKey: String, remoteKey: String) {
        copyIdentity(localKey, remoteKey)
    }
}
