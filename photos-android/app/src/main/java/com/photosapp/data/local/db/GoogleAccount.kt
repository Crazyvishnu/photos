package com.photosapp.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "google_accounts")
data class GoogleAccount(
    @PrimaryKey
    val email: String,
    val totalStorageLimit: Long? = null,
    val storageUsage: Long = 0,
    val isPrimary: Boolean = false,
    val displayName: String? = null,
    val photoUrl: String? = null
)
