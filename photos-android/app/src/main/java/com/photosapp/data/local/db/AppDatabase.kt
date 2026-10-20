package com.photosapp.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun fromSyncState(value: SyncState): String = value.name

    @TypeConverter
    fun toSyncState(value: String): SyncState = enumValueOf<SyncState>(value)
}

@Database(entities = [SyncStatus::class, GoogleAccount::class, CloudMedia::class, Album::class, AlbumMember::class, SharedMedia::class, MediaDate::class, CloudCursor::class, MediaHash::class, MediaFavorite::class, TrashEntry::class, TrashAlias::class, UploadSession::class], version = 8, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao
    abstract fun accountDao(): AccountDao
    abstract fun cloudMediaDao(): CloudMediaDao
    abstract fun albumDao(): AlbumDao
    abstract fun sharedMediaDao(): SharedMediaDao
    abstract fun mediaDateDao(): MediaDateDao
    abstract fun libraryStateDao(): LibraryStateDao
    abstract fun uploadSessionDao(): UploadSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "photos_database"
                )
                .addMigrations(object : androidx.room.migration.Migration(2, 3) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS cloud_media (accountEmail TEXT NOT NULL, fileId TEXT NOT NULL, name TEXT NOT NULL, mimeType TEXT NOT NULL, capturedAt INTEGER NOT NULL, size INTEGER NOT NULL, sha256 TEXT, thumbnailLink TEXT, PRIMARY KEY(accountEmail, fileId))")
                        db.execSQL("ALTER TABLE sync_status ADD COLUMN accountEmail TEXT")
                    }
                }, object : androidx.room.migration.Migration(3, 4) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE sync_status ADD COLUMN errorMessage TEXT")
                    }
                }, object : androidx.room.migration.Migration(4, 5) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE google_accounts ADD COLUMN displayName TEXT")
                        db.execSQL("ALTER TABLE google_accounts ADD COLUMN photoUrl TEXT")
                        db.execSQL("ALTER TABLE cloud_media ADD COLUMN sourceAlbum TEXT NOT NULL DEFAULT ''")
                        db.execSQL("CREATE TABLE IF NOT EXISTS albums (id TEXT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS album_members (albumId TEXT NOT NULL, mediaKey TEXT NOT NULL, PRIMARY KEY(albumId, mediaKey))")
                    }
                }, object : androidx.room.migration.Migration(5, 6) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS shared_media (uri TEXT NOT NULL, storageName TEXT NOT NULL, name TEXT NOT NULL, mimeType TEXT NOT NULL, capturedAt INTEGER NOT NULL, dateSource TEXT NOT NULL, sha256 TEXT NOT NULL, PRIMARY KEY(uri))")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_shared_media_sha256 ON shared_media(sha256)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS media_dates (mediaKey TEXT NOT NULL, capturedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, pendingSync INTEGER NOT NULL, PRIMARY KEY(mediaKey))")
                        db.execSQL("ALTER TABLE cloud_media ADD COLUMN dateEditedAt INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE cloud_media ADD COLUMN dateSource TEXT NOT NULL DEFAULT ''")
                    }
                }, object : androidx.room.migration.Migration(6, 7) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS cloud_cursors (email TEXT NOT NULL, token TEXT NOT NULL, PRIMARY KEY(email))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS media_hashes (uri TEXT NOT NULL, fingerprint TEXT NOT NULL, sha256 TEXT NOT NULL, PRIMARY KEY(uri))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS media_favorites (mediaKey TEXT NOT NULL, favorite INTEGER NOT NULL, updatedAt INTEGER NOT NULL, pending INTEGER NOT NULL, PRIMARY KEY(mediaKey))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS trash_entries (entryKey TEXT NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL, capturedAt INTEGER NOT NULL, isVideo INTEGER NOT NULL, accountEmail TEXT, fileId TEXT, thumbnailLink TEXT, localUris TEXT NOT NULL, trashedAt INTEGER NOT NULL, permanent INTEGER NOT NULL, pendingAction TEXT NOT NULL, error TEXT, PRIMARY KEY(entryKey))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS trash_aliases (mediaKey TEXT NOT NULL, entryKey TEXT NOT NULL, PRIMARY KEY(mediaKey))")
                        db.execSQL("ALTER TABLE cloud_media ADD COLUMN isTrashed INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE cloud_media ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE cloud_media ADD COLUMN favoriteEditedAt INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_media_sha256 ON cloud_media(sha256)")
                    }
                }, object : androidx.room.migration.Migration(7, 8) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS upload_sessions (localUri TEXT NOT NULL, accountEmail TEXT NOT NULL, sha256 TEXT NOT NULL, size INTEGER NOT NULL, fileId TEXT NOT NULL, sessionUrl TEXT, createdAt INTEGER NOT NULL, PRIMARY KEY(localUri))")
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
