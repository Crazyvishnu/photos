package com.photosapp.data.local.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Rebuildable index; originals and their metadata remain in the owning Google Drive. */
@Entity(tableName = "cloud_media", primaryKeys = ["accountEmail", "fileId"], indices = [Index(value = ["sha256"])])
data class CloudMedia(
    val accountEmail: String,
    val fileId: String,
    val name: String,
    val mimeType: String,
    val capturedAt: Long,
    val size: Long,
    val sha256: String?,
    val thumbnailLink: String?,
    @ColumnInfo(defaultValue = "''") val sourceAlbum: String = "",
    @ColumnInfo(defaultValue = "0") val dateEditedAt: Long = 0,
    @ColumnInfo(defaultValue = "''") val dateSource: String = "",
    @ColumnInfo(defaultValue = "0") val isTrashed: Boolean = false,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val favoriteEditedAt: Long = 0
)

@Dao
abstract class CloudMediaDao {
    @Query("SELECT * FROM cloud_media ORDER BY capturedAt DESC")
    abstract fun observeAll(): Flow<List<CloudMedia>>
    @Query("SELECT * FROM cloud_media WHERE sha256 = :hash AND isTrashed = 0") abstract fun findHash(hash: String): List<CloudMedia>
    @Query("SELECT * FROM cloud_media WHERE sha256 = :hash ORDER BY isTrashed ASC") abstract fun findAnyHash(hash: String): List<CloudMedia>
    @Query("SELECT * FROM cloud_media WHERE accountEmail = :email AND fileId = :id") abstract fun get(email: String, id: String): CloudMedia?
    @Query("SELECT * FROM cloud_media WHERE accountEmail = :email AND thumbnailLink = :url LIMIT 1") abstract fun forThumbnail(email: String, url: String): CloudMedia?
    @Query("UPDATE cloud_media SET thumbnailLink = :url WHERE accountEmail = :email AND fileId = :id") abstract fun updateThumbnail(email: String, id: String, url: String)
    @Query("DELETE FROM cloud_media WHERE accountEmail = :email AND fileId = :id") abstract fun delete(email: String, id: String)
    @Query("UPDATE cloud_media SET isTrashed = :trashed WHERE accountEmail = :email AND fileId = :id") abstract fun updateTrash(email: String, id: String, trashed: Boolean)
    @Query("UPDATE cloud_media SET favorite = :favorite, favoriteEditedAt = :version WHERE accountEmail = :email AND fileId = :id") abstract fun updateFavorite(email: String, id: String, favorite: Boolean, version: Long)

    @Query("DELETE FROM cloud_media WHERE accountEmail = :email")
    abstract fun deleteAccount(email: String)

    @Query("UPDATE cloud_media SET capturedAt = :date, dateEditedAt = :editedAt, dateSource = 'edited' WHERE accountEmail = :email AND fileId = :fileId AND dateEditedAt <= :editedAt")
    abstract fun updateDate(email: String, fileId: String, date: Long, editedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertAll(items: List<CloudMedia>)

    @Transaction
    open fun replaceAccount(email: String, items: List<CloudMedia>) {
        deleteAccount(email)
        insertAll(items)
    }
}
