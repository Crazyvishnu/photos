package com.photosapp.ui.home

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photosapp.data.api.ApiService
import com.photosapp.data.api.StorageQuota
import com.photosapp.data.local.db.SyncDao
import com.photosapp.data.local.db.SyncState
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class LocalPhoto(
    val uri: Uri,
    val name: String,
    val dateAdded: Long,
    val isVideo: Boolean,
    val syncState: SyncState,
    val accountEmail: String? = null,
    val thumbnailLink: String? = null,
    val uploadProgress: Int? = null,
    val backupPhase: String? = null,
    val backupError: String? = null,
    val folderPath: String = "",
    val remoteKey: String? = null,
    val dateSource: String = "",
    val cloudDateEditedAt: Long = 0,
    val dateSyncPending: Boolean = false,
    val isFavorite: Boolean = false,
    val favoriteEditedAt: Long = 0
)

sealed class GridItem {
    data class Header(val title: String, val id: String) : GridItem()
    data class Photo(val photo: LocalPhoto) : GridItem()
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class HomeViewModel(
    private val appContext: Context = ServiceLocator.context,
    private val syncDao: SyncDao = ServiceLocator.db.syncDao()
) : ViewModel() {

    private val _storageQuota = MutableStateFlow<StorageQuota?>(null)
    val storageQuota = _storageQuota.asStateFlow()

    private val _accountList = MutableStateFlow<List<com.photosapp.data.local.db.GoogleAccount>>(emptyList())
    val accountList = _accountList.asStateFlow()

    private val _photos = MutableStateFlow<List<LocalPhoto>>(emptyList())
    private val localPhotos = combine(_photos, ServiceLocator.db.sharedMediaDao().observeAll()) { device, shared ->
        device + shared.map { item -> LocalPhoto(Uri.parse(item.uri), item.name, item.capturedAt, item.mimeType.startsWith("video/"),
            SyncState.IDLE, folderPath = appContext.getString(com.photosapp.R.string.shared_folder), dateSource = item.dateSource) }
    }
    private val mediaLoaded = MutableStateFlow(false)
    private val workManager = androidx.work.WorkManager.getInstance(appContext)
    private val runtime = combine(
        workManager.getWorkInfosForUniqueWorkFlow("photo_backup_immediate"),
        workManager.getWorkInfosForUniqueWorkFlow(com.photosapp.worker.BackupWorker.WORK_NAME),
        ServiceLocator.prefs.backupPaused, ServiceLocator.prefs.wifiOnly, observeBackupNetwork(appContext)
    ) { manual, scheduled, paused, wifi, network ->
        BackupRuntime(manual + scheduled, paused, wifi, network)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, BackupRuntime())
    private var photoLoadJob: kotlinx.coroutines.Job? = null
    private val mediaObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { loadPhotos() }
    }
    val photos = combine(localPhotos, syncDao.observeAllStatuses().sample(250), ServiceLocator.db.cloudMediaDao().observeAll(),
        ServiceLocator.db.accountDao().observeAllAccounts(), runtime) { local, statuses, remote, accounts, work ->
        val linked = accounts.map { it.email }.toSet()
        val statusMap = statuses.associateBy { it.localUri }
        val cloudMap = remote.associateBy { "drive:${it.accountEmail}:${it.fileId}" }
        val localIds = local.mapNotNull { statusMap[it.uri.toString()]?.takeIf { s -> s.accountEmail in linked }?.driveFileId }.toSet()
        val localItems = local.map { photo ->
            val status = statusMap[photo.uri.toString()]
            val remoteKey = if (status?.driveFileId != null && status.accountEmail != null) "drive:${status.accountEmail}:${status.driveFileId}" else null
            val storedDate = cloudMap[remoteKey]
            val useStored = storedDate != null && (storedDate.dateEditedAt > 0 ||
                (photo.dateSource == "received" && storedDate.dateSource.isNotBlank() && storedDate.dateSource != "received"))
            val progress = work.active?.progress?.takeIf { it.getString(com.photosapp.worker.BackupWorker.KEY_URI) == photo.uri.toString() }
            val state = when {
                status?.status == SyncState.SUCCESS && (status.accountEmail !in linked || status.driveFileId == null) -> SyncState.IDLE
                status?.status == SyncState.UPLOADING && progress == null -> SyncState.PENDING
                else -> status?.status ?: SyncState.IDLE
            }
            photo.copy(syncState = state,
                dateAdded = if (useStored) requireNotNull(storedDate).capturedAt else photo.dateAdded,
                dateSource = if (useStored) requireNotNull(storedDate).dateSource else photo.dateSource,
                cloudDateEditedAt = storedDate?.dateEditedAt ?: 0,
                isFavorite = storedDate?.favorite ?: false, favoriteEditedAt = storedDate?.favoriteEditedAt ?: 0,
                uploadProgress = progress?.getInt(com.photosapp.worker.BackupWorker.KEY_PERCENT, 0),
                backupPhase = progress?.getString(com.photosapp.worker.BackupWorker.KEY_PHASE),
                backupError = status?.errorMessage,
                remoteKey = remoteKey)
        }
        val cloudItems = remote.filter { !it.isTrashed && it.accountEmail in linked && it.fileId !in localIds }
            .distinctBy { it.sha256 ?: "${it.accountEmail}/${it.fileId}" }.map { item ->
                LocalPhoto(Uri.parse("https://www.googleapis.com/drive/v3/files/${item.fileId}?alt=media"),
                    item.name, item.capturedAt, item.mimeType.startsWith("video/"), SyncState.SUCCESS,
                    item.accountEmail, item.thumbnailLink, folderPath = item.sourceAlbum, remoteKey = "drive:${item.accountEmail}:${item.fileId}",
                    dateSource = item.dateSource, cloudDateEditedAt = item.dateEditedAt, isFavorite = item.favorite, favoriteEditedAt = item.favoriteEditedAt)
            }
        localItems.distinctBy { if (it.syncState == SyncState.SUCCESS) it.remoteKey ?: it.uri.toString() else it.uri.toString() } + cloudItems
    }.combine(ServiceLocator.db.mediaDateDao().observeAll()) { all, dates ->
        val overrides = dates.associateBy { it.mediaKey }
        all.map { photo ->
            val date = listOfNotNull(overrides[photo.uri.toString()], photo.remoteKey?.let { overrides[it] }).maxByOrNull { it.updatedAt }
            if (date != null && date.updatedAt >= photo.cloudDateEditedAt) photo.copy(dateAdded = date.capturedAt, dateSource = "edited", dateSyncPending = date.pendingSync)
            else photo
        }.sortedWith(compareByDescending<LocalPhoto> { it.dateAdded }.thenBy { it.uri.toString() })
    }.combine(combine(ServiceLocator.db.libraryStateDao().observeFavorites(), ServiceLocator.db.libraryStateDao().observeTrashAliases()) { favorites, aliases -> favorites to aliases }) { all, state ->
        val flags = state.first.associateBy { it.mediaKey }
        val hidden = state.second.map { it.mediaKey }.toSet()
        all.filter { it.uri.toString() !in hidden && it.remoteKey !in hidden }.map { photo ->
            val flag = listOfNotNull(flags[photo.uri.toString()], flags[photo.remoteKey]).maxByOrNull { it.updatedAt }
            if (flag != null && flag.updatedAt >= photo.favoriteEditedAt) photo.copy(isFavorite = flag.favorite, favoriteEditedAt = flag.updatedAt) else photo
        }
    }.combine(ServiceLocator.prefs.localMode) { all, localOnly -> if (localOnly) all.filter { it.accountEmail == null } else all }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    val albums = combine(photos, ServiceLocator.db.albumDao().observeAlbums(),
        ServiceLocator.db.albumDao().observeMembers(), ServiceLocator.prefs.automaticAlbums,
        ServiceLocator.prefs.hiddenAlbums) { media, custom, members, automatic, hidden ->
        val definitions = (if (automatic) listOf(
            com.photosapp.data.local.db.Album("auto:favorites", "Favorites", 0),
            com.photosapp.data.local.db.Album("auto:screenshots", "Screenshots", 0),
            com.photosapp.data.local.db.Album("auto:documents", "Documents", 0),
            com.photosapp.data.local.db.Album("auto:videos", "Videos", 0)
        ) else emptyList()) + custom
        val membership = members.groupBy { it.albumId }.mapValues { (_, rows) -> rows.map { it.mediaKey }.toSet() }
        definitions.filter { it.id !in hidden }.map { album ->
            val keys = membership[album.id].orEmpty()
            AlbumCollection(album.id, album.title, media.filter { photo ->
                photo.uri.toString() in keys || photo.remoteKey in keys || when (album.id) {
                    "auto:favorites" -> photo.isFavorite
                    "auto:screenshots" -> (photo.folderPath + "/" + photo.name).contains("screenshot", true) || photo.name.startsWith("screen_", true)
                    "auto:documents" -> Regex("(?i)(document|scan|receipt)").containsMatchIn(photo.folderPath + "/" + photo.name)
                    "auto:videos" -> photo.isVideo
                    else -> false
                }
            }, album.id.startsWith("auto:"))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    fun createAlbum(title: String, uris: Set<String> = emptySet(), onCreated: (String) -> Unit = {}) {
        val name = title.trim().take(100)
        if (name.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val id = java.util.UUID.randomUUID().toString()
            ServiceLocator.db.albumDao().save(com.photosapp.data.local.db.Album(id, name))
            addToAlbum(id, uris)
            withContext(Dispatchers.Main) { onCreated(id) }
        }
    }
    suspend fun addToAlbum(id: String, uris: Set<String>) {
        val selected = photos.value.filter { it.uri.toString() in uris }
        ServiceLocator.db.albumDao().add(selected.flatMap { photo ->
            listOfNotNull(photo.uri.toString(), photo.remoteKey).distinct().map {
                com.photosapp.data.local.db.AlbumMember(id, it)
            }
        })
    }
    fun viewModelScopeLaunchAdd(id: String, uris: Set<String>, done: () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        addToAlbum(id, uris); clearSelection(); withContext(Dispatchers.Main) { done() }
    }
    fun deleteAlbum(album: AlbumCollection) = viewModelScope.launch(Dispatchers.IO) {
        if (album.automatic) ServiceLocator.prefs.hideAlbum(album.id) else ServiceLocator.db.albumDao().delete(album.id)
    }
    fun removeFromAlbum(id: String, photo: LocalPhoto) = viewModelScope.launch(Dispatchers.IO) {
        ServiceLocator.db.albumDao().remove(id, listOfNotNull(photo.uri.toString(), photo.remoteKey))
    }

    val backupSummary = combine(photos, runtime, mediaLoaded, accountList) { items, work, loaded, accounts ->
        val local = items.filter { it.accountEmail == null }
        val counts = local.groupingBy { it.syncState }.eachCount()
        val active = local.firstOrNull { it.syncState == SyncState.UPLOADING }
        BackupSummary(counts[SyncState.SUCCESS] ?: 0, counts[SyncState.UPLOADING] ?: 0,
            counts[SyncState.PENDING] ?: 0, counts[SyncState.IDLE] ?: 0, counts[SyncState.FAILED] ?: 0,
            loaded, accounts.isNotEmpty(), work, active?.name,
            active?.uploadProgress?.takeIf { active.backupPhase == com.photosapp.worker.BackupWorker.PHASE_UPLOADING })
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, BackupSummary())

    private val _cloudMessage = MutableStateFlow("Your photos, across your accounts")
    val cloudMessage = _cloudMessage.asStateFlow()
    private var refreshJob: kotlinx.coroutines.Job? = null
    fun refreshCloud() {
        com.photosapp.worker.MediaActionsWorker.enqueue(appContext)
        com.photosapp.worker.DateSyncWorker.enqueue(appContext)
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
        _cloudMessage.value = "Refreshing cloud photos…"
            val failed = com.photosapp.data.CloudLibraryRepository().refresh()
            _cloudMessage.value = if (failed.isEmpty()) "Cloud photos refreshed · tap to refresh"
                else "Couldn't sync ${failed.joinToString()}. Tap to retry."
        }
    }

    private val search = MutableStateFlow("")
    private val libraryFilter = MutableStateFlow("all")
    private val backupFilter = MutableStateFlow<SyncState?>(null)
    val currentBackupFilter: SyncState? get() = backupFilter.value
    fun setBackupFilter(value: SyncState?) { backupFilter.value = value; clearSelection() }
    fun setSearch(value: String) { search.value = value }
    fun setLibraryFilter(value: String) { libraryFilter.value = value; clearSelection() }

    val gridItems = combine(photos, search, libraryFilter, backupFilter) { all, query, filter, backupState ->
        val list = all.filter { photo ->
            (backupState == null || photo.syncState == backupState) && photo.name.contains(query, ignoreCase = true) && when (filter) {
                "cloud" -> photo.syncState == SyncState.SUCCESS
                "device" -> photo.accountEmail == null
                else -> true
            }
        }
        val items = mutableListOf<GridItem>()
        if (list.isNotEmpty()) {
            val zone = java.time.ZoneId.systemDefault()
            val groups = list.groupBy { java.time.Instant.ofEpochSecond(it.dateAdded).atZone(zone).toLocalDate() }
            val format = java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
            for ((dayId, photos) in groups.toSortedMap(compareByDescending { it })) {
                val dateStr = when (dayId) {
                    java.time.LocalDate.now(zone) -> "Today"
                    java.time.LocalDate.now(zone).minusDays(1) -> "Yesterday"
                    else -> dayId.format(format)
                }
                items.add(GridItem.Header(dateStr, dateStr))
                items.addAll(photos.map { GridItem.Photo(it) })
            }
        }
        items
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    private val _selectedPhotos = MutableStateFlow<Set<String>>(emptySet())
    val selectedPhotos = _selectedPhotos.asStateFlow()

    fun toggleSelection(uri: String) {
        val current = _selectedPhotos.value
        if (current.contains(uri)) {
            _selectedPhotos.value = current - uri
        } else {
            _selectedPhotos.value = current + uri
        }
    }

    fun setSelection(uri: String, selected: Boolean) {
        val current = _selectedPhotos.value
        if (selected) {
            _selectedPhotos.value = current + uri
        } else {
            _selectedPhotos.value = current - uri
        }
    }

    fun clearSelection() {
        _selectedPhotos.value = emptySet()
    }

    fun backupSelected(uris: List<String>? = null, retryNow: Boolean = false) {
        val urisToBackup = (uris ?: _selectedPhotos.value.toList()).filter { Uri.parse(it).scheme == "content" }
        if (urisToBackup.isEmpty()) return

        clearSelection()

        // Pass to BackupWorker (we can update sync statuses to PENDING immediately to show UI feedback)
        viewModelScope.launch(Dispatchers.IO) {
            resumeScheduledBackup()
            urisToBackup.forEach { uri ->
                val current = syncDao.getStatus(uri)
                if (current == null || current.status == SyncState.FAILED || current.status == SyncState.IDLE ||
                    (current.status == SyncState.SUCCESS && current.accountEmail !in _accountList.value.map { it.email })) {
                    syncDao.insert((current ?: com.photosapp.data.local.db.SyncStatus(localUri = uri)).copy(status = SyncState.PENDING, errorMessage = null))
                }
            }
            // Trigger BackupWorker immediately
            androidx.work.WorkManager.getInstance(appContext).enqueueUniqueWork(
                "photo_backup_immediate",
                if (retryNow) androidx.work.ExistingWorkPolicy.REPLACE else androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                androidx.work.OneTimeWorkRequestBuilder<com.photosapp.worker.BackupWorker>()
                    .addTag(com.photosapp.worker.BackupWorker.MANUAL_TAG)
                    .setInputData(androidx.work.workDataOf(com.photosapp.worker.BackupWorker.KEY_SELECTED_ONLY to true))
                    .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build()
            )
        }
    }

    fun retryFailed() {
        val failed=photos.value.filter { it.accountEmail==null && it.syncState==SyncState.FAILED }.map { it.uri.toString() }
        backupSelected(failed, retryNow=true)
    }

    fun backupAll() {
        viewModelScope.launch(Dispatchers.IO) {
            resumeScheduledBackup()
            // Discover existing media in the worker, even before the gallery finishes loading.
            androidx.work.WorkManager.getInstance(appContext).enqueueUniqueWork(
                "photo_backup_immediate",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequestBuilder<com.photosapp.worker.BackupWorker>()
                    .addTag(com.photosapp.worker.BackupWorker.MANUAL_TAG)
                    .setInputData(androidx.work.workDataOf(com.photosapp.worker.BackupWorker.KEY_ALL_DEVICE_MEDIA to true))
                    .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build()
            )
        }
    }

    private suspend fun resumeScheduledBackup() {
        ServiceLocator.prefs.setLocalMode(false)
        ServiceLocator.prefs.setBackupPaused(false)
        androidx.work.WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            com.photosapp.worker.BackupWorker.WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            com.photosapp.worker.BackupWorker.buildRequest(ServiceLocator.prefs.wifiOnly.first())
        )
    }

    fun stopBackup() {
        androidx.work.WorkManager.getInstance(appContext).cancelUniqueWork("photo_backup_immediate")
        androidx.work.WorkManager.getInstance(appContext).cancelUniqueWork(com.photosapp.worker.BackupWorker.WORK_NAME)
        viewModelScope.launch(Dispatchers.IO) {
            ServiceLocator.prefs.setBackupPaused(true)
            val statuses = syncDao.getByState(SyncState.PENDING) + syncDao.getByState(SyncState.UPLOADING)
            statuses.forEach {
                syncDao.insert(it.copy(status = SyncState.IDLE))
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            ServiceLocator.prefs.enableMobileBackupForUpgrade()
            if (!ServiceLocator.prefs.backupPaused.first()) {
                workManager.enqueueUniquePeriodicWork(com.photosapp.worker.BackupWorker.WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                    com.photosapp.worker.BackupWorker.buildRequest(ServiceLocator.prefs.wifiOnly.first()))
            }
        }
        appContext.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        appContext.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        viewModelScope.launch(Dispatchers.IO) {
            ServiceLocator.db.accountDao().observeAllAccounts().collect { accounts ->
                _accountList.value = accounts
                _storageQuota.value = if (accounts.isEmpty()) null else StorageQuota(
                    limit = if (accounts.all { it.totalStorageLimit != null }) accounts.sumOf { it.totalStorageLimit ?: 0 } else null,
                    usage = accounts.sumOf { it.storageUsage },
                    usageInDrive = accounts.sumOf { it.storageUsage }, usageInDriveTrash = 0
                )
            }
        }
    }

    fun loadStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            for (account in ServiceLocator.db.accountDao().getAllAccounts()) {
                try {
                    val quota = ServiceLocator.getApiForAccount(account.email).getDriveAbout().storageQuota
                    if (ServiceLocator.db.accountDao().getAccount(account.email) != null) {
                        ServiceLocator.db.accountDao().updateQuota(account.email, quota.limit, quota.usage)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.e("HomeVM", "Unable to refresh quota for ${account.email}", e) }
            }
        }
    }

    fun loadPhotos() {
        photoLoadJob?.cancel()
        photoLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val localItems = mutableListOf<LocalPhoto>()
                val uri = MediaStore.Files.getContentUri("external")
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.Images.ImageColumns.DATE_TAKEN,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                    if (android.os.Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
                )

                val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
                val selectionArgs = arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                )

                val cursor = appContext.contentResolver.query(
                    uri, projection, selection, selectionArgs,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )

                cursor?.use { c ->
                    val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dateIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    val takenIdx = c.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN)
                    val mimeIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    val folderIdx = c.getColumnIndex(if (android.os.Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA)

                    while (c.moveToNext()) {
                        val id = c.getLong(idIdx)
                        val name = c.getString(nameIdx) ?: "unknown"
                        val taken = if (takenIdx >= 0 && !c.isNull(takenIdx)) c.getLong(takenIdx).takeIf { it > 0 } else null
                        val date = taken?.div(1000L) ?: c.getLong(dateIdx)
                        val type = c.getInt(mimeIdx)
                        val isVideo = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        val itemUri = android.content.ContentUris.withAppendedId(collection, id)

                        localItems.add(LocalPhoto(itemUri, name, date, isVideo, SyncState.IDLE,
                            folderPath = if (folderIdx >= 0) c.getString(folderIdx).orEmpty() else "", dateSource = "device"))
                    }
                }

                withContext(Dispatchers.Main) {
                    _photos.value = localItems
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: SecurityException) { _photos.value = emptyList() }
            catch (e: Exception) {
                android.util.Log.e("HomeVM", "Photos failed", e)
            } finally { if (kotlin.coroutines.coroutineContext.isActive) mediaLoaded.value = true }
        }
    }

    override fun onCleared() {
        appContext.contentResolver.unregisterContentObserver(mediaObserver)
        super.onCleared()
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel() as T
        }
    }
}
