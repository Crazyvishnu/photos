package com.photosapp.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_status")
data class SyncStatus(
    @PrimaryKey val localUri: String,
    val driveFileId: String? = null,
    val status: SyncState = SyncState.PENDING,
    val lastAttemptTimeMs: Long = 0,
    val mimeType: String? = null,
    val accountEmail: String? = null,
    val errorMessage: String? = null
)

enum class SyncState {
    IDLE,
    PENDING,
    UPLOADING,
    SUCCESS,
    FAILED
}
