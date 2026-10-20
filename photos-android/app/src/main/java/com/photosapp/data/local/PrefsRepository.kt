package com.photosapp.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "photos_prefs")

/**
 * Single source of truth for all local preferences.
 * No Hilt — context is passed directly from ServiceLocator.
 */
class PrefsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    private val JWT_TOKEN           = stringPreferencesKey("jwt_token") // We'll use this for the Google Access Token
    private val REFRESH_TOKEN       = stringPreferencesKey("refresh_token")
    private val GOOGLE_EMAIL        = stringPreferencesKey("google_email")
    private val BACKUP_CAMERA_ROLL  = booleanPreferencesKey("backup_camera_roll")
    private val BACKUP_DOWNLOADS    = booleanPreferencesKey("backup_downloads")
    private val BACKUP_WHATSAPP     = booleanPreferencesKey("backup_whatsapp")
    private val BACKFILL_DONE       = booleanPreferencesKey("backfill_done")
    private val WIFI_ONLY           = booleanPreferencesKey("wifi_only")
    private val BACKUP_PAUSED       = booleanPreferencesKey("backup_paused")
    private val MOBILE_DATA_ENABLED = booleanPreferencesKey("mobile_data_enabled")
    private val THEME = stringPreferencesKey("theme")
    private val GRID = androidx.datastore.preferences.core.intPreferencesKey("grid_columns")
    private val BADGES = booleanPreferencesKey("backup_badges")
    private val AUTOMATIC_ALBUMS = booleanPreferencesKey("automatic_albums")
    private val HIDDEN_ALBUMS = androidx.datastore.preferences.core.stringSetPreferencesKey("hidden_albums")
    private val LOCAL_MODE = booleanPreferencesKey("local_mode")
    private val ALL_FOLDERS = booleanPreferencesKey("all_folders")

    val theme = store.data.map { it[THEME] ?: "system" }
    val gridColumns = store.data.map { (it[GRID] ?: 3).coerceIn(2, 4) }
    val showBackupBadges = store.data.map { it[BADGES] ?: true }
    val automaticAlbums = store.data.map { it[AUTOMATIC_ALBUMS] ?: true }
    val hiddenAlbums = store.data.map { it[HIDDEN_ALBUMS] ?: emptySet() }
    val localMode = store.data.map { it[LOCAL_MODE] ?: false }
    val backupAllFolders = store.data.map { it[ALL_FOLDERS] ?: false }
    suspend fun setTheme(value: String) = store.edit { it[THEME] = value }
    suspend fun setGridColumns(value: Int) = store.edit { it[GRID] = value.coerceIn(2, 4) }
    suspend fun setShowBackupBadges(value: Boolean) = store.edit { it[BADGES] = value }
    suspend fun setAutomaticAlbums(value: Boolean) = store.edit { it[AUTOMATIC_ALBUMS] = value }
    suspend fun hideAlbum(id: String) = store.edit { it[HIDDEN_ALBUMS] = (it[HIDDEN_ALBUMS] ?: emptySet()) + id }
    suspend fun restoreAutomaticAlbums() = store.edit { it.remove(HIDDEN_ALBUMS); it[AUTOMATIC_ALBUMS] = true }
    suspend fun setLocalMode(value: Boolean) = store.edit { it[LOCAL_MODE] = value }
    suspend fun setBackupAllFolders(value: Boolean) = store.edit { it[ALL_FOLDERS] = value }

    val jwtToken: Flow<String?>    = store.data.map { it[JWT_TOKEN] }
    val refreshToken: Flow<String?> = store.data.map { it[REFRESH_TOKEN] }
    val googleEmail: Flow<String?> = store.data.map { it[GOOGLE_EMAIL] }
    val isLoggedIn: Flow<Boolean> = kotlinx.coroutines.flow.combine(googleEmail, localMode) { email, local -> local || !email.isNullOrBlank() }

    suspend fun getJwtToken(): String? = jwtToken.firstOrNull()
    suspend fun getRefreshToken(): String? = refreshToken.firstOrNull()

    suspend fun saveAuth(token: String, refresh: String?, email: String) {
        store.edit {
            it[JWT_TOKEN] = token
            if (refresh != null) it[REFRESH_TOKEN] = refresh
            it[GOOGLE_EMAIL] = email
            it[LOCAL_MODE] = false
        }
    }

    suspend fun clearAuth() {
        store.edit { it.remove(JWT_TOKEN); it.remove(REFRESH_TOKEN); it.remove(GOOGLE_EMAIL); it[BACKFILL_DONE] = false }
    }

    val backupCameraRoll: Flow<Boolean> = store.data.map { it[BACKUP_CAMERA_ROLL] ?: true }
    val backupDownloads: Flow<Boolean>  = store.data.map { it[BACKUP_DOWNLOADS] ?: false }
    val backupWhatsApp: Flow<Boolean>   = store.data.map { it[BACKUP_WHATSAPP] ?: false }
    // The user enabled mobile backup. Persist this migration for existing installs as well.
    val wifiOnly: Flow<Boolean>         = store.data.map { prefs ->
        if (prefs[MOBILE_DATA_ENABLED] == true) prefs[WIFI_ONLY] ?: false else false
    }
    val backupPaused: Flow<Boolean>     = store.data.map { it[BACKUP_PAUSED] ?: false }
    suspend fun setBackupPaused(value: Boolean) = store.edit { it[BACKUP_PAUSED] = value }
    val backfillDone: Flow<Boolean>     = store.data.map { it[BACKFILL_DONE] ?: false }

    suspend fun setBackupCameraRoll(v: Boolean) = store.edit { it[BACKUP_CAMERA_ROLL] = v }
    suspend fun setWifiOnly(v: Boolean)         = store.edit { it[WIFI_ONLY] = v; it[MOBILE_DATA_ENABLED] = true }
    suspend fun enableMobileBackupForUpgrade() = store.edit {
        if (it[MOBILE_DATA_ENABLED] != true) { it[WIFI_ONLY] = false; it[MOBILE_DATA_ENABLED] = true }
    }
    suspend fun setBackfillDone(v: Boolean)     = store.edit { it[BACKFILL_DONE] = v }
}
