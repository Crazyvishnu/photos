package com.photosapp.data.local.db

import androidx.room.*

/** Private resumable upload URL; never include this row in diagnostics or logs. */
@Entity(tableName = "upload_sessions")
data class UploadSession(
    @PrimaryKey val localUri: String,
    val accountEmail: String,
    val sha256: String,
    val size: Long,
    val fileId: String,
    val sessionUrl: String?,
    val createdAt: Long
)

@Dao
interface UploadSessionDao {
    @Query("SELECT * FROM upload_sessions WHERE localUri = :uri")
    fun get(uri: String): UploadSession?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(session: UploadSession)
    @Query("DELETE FROM upload_sessions WHERE localUri = :uri")
    fun delete(uri: String)
}
