package com.photosapp.data.local.db

import androidx.room.*

@Entity(tableName = "album_cloud_state")
data class AlbumCloudState(@PrimaryKey val id: String, val updatedAt: Long, val pending: Boolean = true,
    val deleted: Boolean = false, val email: String? = null, val fileId: String? = null)

@Dao
interface AlbumCloudDao {
    @Query("SELECT * FROM album_cloud_state WHERE id = :id") fun get(id: String): AlbumCloudState?
    @Query("SELECT * FROM album_cloud_state WHERE pending = 1") fun pending(): List<AlbumCloudState>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun save(state: AlbumCloudState)
}
