package com.photosapp.data.local.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Durable copies of files received with temporary Android share grants. */
@Entity(tableName = "shared_media", indices = [Index(value = ["sha256"], unique = true)])
data class SharedMedia(
    @PrimaryKey val uri: String,
    val storageName: String,
    val name: String,
    val mimeType: String,
    val capturedAt: Long,
    val dateSource: String,
    val sha256: String
)

@Dao
interface SharedMediaDao {
    @Query("DELETE FROM shared_media WHERE uri = :uri") fun delete(uri: String)
    @Query("SELECT * FROM shared_media ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<SharedMedia>>
    @Query("SELECT * FROM shared_media")
    fun getAll(): List<SharedMedia>
    @Query("SELECT * FROM shared_media WHERE uri = :uri")
    fun get(uri: String): SharedMedia?
    @Query("SELECT * FROM shared_media WHERE sha256 = :hash")
    fun getByHash(hash: String): SharedMedia?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(item: SharedMedia): Long
}

@Entity(tableName = "media_dates")
data class MediaDate(@PrimaryKey val mediaKey: String, val capturedAt: Long, val updatedAt: Long, val pendingSync: Boolean = true)

@Dao
abstract class MediaDateDao {
    @Query("SELECT * FROM media_dates")
    abstract fun observeAll(): Flow<List<MediaDate>>
    @Query("SELECT * FROM media_dates WHERE mediaKey = :key")
    abstract fun get(key: String): MediaDate?
    @Query("SELECT * FROM media_dates WHERE pendingSync = 1")
    abstract fun pending(): List<MediaDate>
    // Reconcile corrections that older versions may have acknowledged on HTTP
    // errors. The worker compares versions before writing anything to Drive.
    @Query("UPDATE media_dates SET pendingSync = 1")
    abstract fun reconcileAfterUpdate()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insert(date: MediaDate)
    @Transaction
    open fun saveLatest(date: MediaDate) {
        val old = get(date.mediaKey)
        if (old == null || old.updatedAt <= date.updatedAt) insert(date)
    }
    @Transaction
    open fun saveAliases(dates: List<MediaDate>) { dates.forEach { saveLatest(it) } }
    @Query("UPDATE media_dates SET pendingSync = 0 WHERE mediaKey = :key AND updatedAt = :version")
    abstract fun markSynced(key: String, version: Long)
    @Transaction
    open fun linkBackup(localKey: String, remoteKey: String) {
        get(localKey)?.let { saveLatest(it.copy(mediaKey = remoteKey, pendingSync = true)) }
    }
}
