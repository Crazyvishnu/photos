package com.photosapp.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_status") fun getAll(): List<SyncStatus>
    @Query("SELECT * FROM sync_status WHERE accountEmail = :email AND driveFileId = :fileId") fun forCloud(email: String, fileId: String): List<SyncStatus>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(status: SyncStatus)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(statuses: List<SyncStatus>)

    @Query("SELECT * FROM sync_status WHERE localUri = :uri")
    fun getStatus(uri: String): SyncStatus?

    @Query("SELECT * FROM sync_status")
    fun observeAllStatuses(): Flow<List<SyncStatus>>

    @Query("SELECT * FROM sync_status WHERE status = :state")
    fun getByState(state: SyncState): List<SyncStatus>

    @Query("UPDATE sync_status SET status = 'FAILED', errorMessage = :message, lastAttemptTimeMs = :time WHERE localUri = :uri")
    fun markFailed(uri: String, message: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE sync_status SET status = 'IDLE', driveFileId = NULL, accountEmail = NULL, errorMessage = NULL WHERE localUri = :uri AND driveFileId = :fileId AND status = 'SUCCESS' AND lastAttemptTimeMs < :before")
    fun invalidateMissingBackup(uri: String, fileId: String, before: Long)

    @Query("UPDATE sync_status SET status = :state, driveFileId = :driveId, lastAttemptTimeMs = :time WHERE localUri = :uri")
    fun updateStatus(uri: String, state: SyncState, driveId: String? = null, time: Long = System.currentTimeMillis())
}
