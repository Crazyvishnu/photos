package com.photosapp.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(account: GoogleAccount)

    @Query("SELECT * FROM google_accounts")
    fun observeAllAccounts(): Flow<List<GoogleAccount>>

    @Query("SELECT * FROM google_accounts")
    fun getAllAccounts(): List<GoogleAccount>

    @Query("SELECT * FROM google_accounts WHERE email = :email")
    fun getAccount(email: String): GoogleAccount?

    @Query("UPDATE google_accounts SET totalStorageLimit = :limit, storageUsage = :usage WHERE email = :email")
    fun updateQuota(email: String, limit: Long?, usage: Long)

    @Query("DELETE FROM google_accounts WHERE email = :email")
    fun deleteAccount(email: String)

    @Query("DELETE FROM google_accounts")
    fun deleteAll()
}
