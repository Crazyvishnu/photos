package com.photosapp.ui.home

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.photosapp.databinding.FragmentHomeBinding
import com.photosapp.worker.BackupWorker
import android.view.ScaleGestureDetector
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.min
import coil.load
import com.photosapp.R
import com.photosapp.data.local.db.SyncState

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val vm: HomeViewModel by activityViewModels { HomeViewModel.Factory }
    private lateinit var adapter: PhotoGridAdapter
    private var currentSpanCount = 3
    private var hideDateRunnable: Runnable? = null
    private var openingViewer = false
    private var draggingFastScroll = false
    private var selectedTab = R.id.nav_photos

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTab = savedInstanceState?.getInt("tab") ?: R.id.nav_photos
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.loadPhotos()
        if (hasMediaAccess()) { scheduleBackup(); requestBackupNotifications() }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGrid()
        observeState()
        requestPermissionsAndSchedule()
        vm.loadStatus()
        vm.loadPhotos()
        vm.refreshCloud()
        binding.tvCloudStatus.setOnClickListener { vm.refreshCloud(); vm.loadStatus() }
        binding.btnMediaAccess.setOnClickListener { requestMediaPermissions() }
        binding.backupDetails.setOnClickListener { showBackupDetails() }
        binding.backupAction.setOnClickListener {
            val status = vm.backupSummary.value
            when {
                !status.hasAccounts -> AccountBottomSheet().show(parentFragmentManager, AccountBottomSheet.TAG)
                status.runtime.paused -> vm.backupAll()
                status.runtime.running -> vm.stopBackup()
                status.remaining > 0 -> vm.backupAll()
                else -> showBackupDetails()
            }
        }
        binding.backupFilters.setOnCheckedStateChangeListener { _, checked ->
            vm.setBackupFilter(when (checked.firstOrNull()) {
                R.id.filter_done -> SyncState.SUCCESS
                R.id.filter_uploading -> SyncState.UPLOADING
                R.id.filter_waiting -> SyncState.PENDING
                R.id.filter_not_done -> SyncState.IDLE
                R.id.filter_failed -> SyncState.FAILED
                else -> null
            })
        }
        binding.backupFilters.check(when (vm.currentBackupFilter) {
            SyncState.SUCCESS -> R.id.filter_done
            SyncState.UPLOADING -> R.id.filter_uploading
            SyncState.PENDING -> R.id.filter_waiting
            SyncState.IDLE -> R.id.filter_not_done
            SyncState.FAILED -> R.id.filter_failed
            else -> R.id.filter_all
        })
        binding.searchPhotos.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                vm.setSearch(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        binding.libraryNavigation.setOnItemSelectedListener { item ->
            selectedTab = item.itemId
            val albums = item.itemId == R.id.nav_albums
            binding.photoContent.visibility = if (albums) View.GONE else View.VISIBLE
            binding.collectionsContainer.visibility = if (albums) View.VISIBLE else View.GONE
            binding.searchContainer.visibility = if (albums) View.GONE else View.VISIBLE
            binding.backupCard.visibility = if (albums) View.GONE else View.VISIBLE
            binding.filtersContainer.visibility = if (albums) View.GONE else View.VISIBLE
            binding.tvCloudStatus.visibility = if (albums) View.GONE else View.VISIBLE
            binding.btnMediaAccess.visibility = if (albums || hasFullMediaAccess()) View.GONE else View.VISIBLE
            binding.libraryTitle.text = when (item.itemId) { R.id.nav_albums -> "Collections"; R.id.nav_cloud -> "Cloud library"; R.id.nav_device -> "On this device"; else -> "Photos" }
            if (albums && childFragmentManager.findFragmentById(R.id.collections_container) == null) {
                childFragmentManager.beginTransaction().replace(R.id.collections_container, AlbumsFragment()).commit()
            }
            vm.setLibraryFilter(when (item.itemId) {
                com.photosapp.R.id.nav_cloud -> "cloud"
                com.photosapp.R.id.nav_device -> "device"
                else -> "all"
            })
            true
        }
        binding.libraryNavigation.selectedItemId = selectedTab
        binding.openBin.setOnClickListener { TrashFragment.open(this) }
        binding.binSelected.setOnClickListener { MediaUiActions.bin(this, vm, vm.photos.value.filter { it.uri.toString() in vm.selectedPhotos.value }) }
        binding.shareSelected.setOnClickListener { MediaUiActions.share(this, vm.photos.value.filter { it.uri.toString() in vm.selectedPhotos.value }) }
        binding.btnAddAlbum.setOnClickListener { AlbumActions.choose(this, vm, vm.selectedPhotos.value) }
    }

    private fun setupGrid() {
        adapter = PhotoGridAdapter(
            onClick = { photo ->
                val index = vm.photos.value.indexOfFirst { it.uri == photo.uri }
                if (index != -1 && !openingViewer && !parentFragmentManager.isStateSaved) {
                    openingViewer = true
                    parentFragmentManager.beginTransaction()
                        .replace(com.photosapp.R.id.fragment_container, PhotoViewerFragment.newInstance(index, photo.uri.toString()))
                        .addToBackStack(null)
                        .commit()
                }
            },
            onLongClick = { photo ->
                vm.toggleSelection(photo.uri.toString())
            },
            isSelectionModeActive = {
                vm.selectedPhotos.value.isNotEmpty()
            },
            isSelected = { photo ->
                vm.selectedPhotos.value.contains(photo.uri.toString())
            }
        )
        val gridLayoutManager = GridLayoutManager(requireContext(), currentSpanCount)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.currentList.getOrNull(position) is GridItem.Header) currentSpanCount else 1
            }
        }
        binding.recyclerView.apply {
            layoutManager = gridLayoutManager
            adapter = this@HomeFragment.adapter
        }
        setupFastScroller()

        // Pinch-to-zoom logic
        val scaleDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var cumulativeScale = 1f
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { cumulativeScale = 1f; return true }
            override fun onScale(detector: ScaleGestureDetector): Boolean { cumulativeScale *= detector.scaleFactor; return true }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                val scaleFactor = cumulativeScale
                if (scaleFactor > 1.2f) { // Zoomed in -> Fewer columns
                    currentSpanCount = max(2, currentSpanCount - 1)
                } else if (scaleFactor < 0.8f) { // Zoomed out -> More columns
                    currentSpanCount = min(4, currentSpanCount + 1)
                }
                gridLayoutManager.spanCount = currentSpanCount
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                viewLifecycleOwner.lifecycleScope.launch { com.photosapp.di.ServiceLocator.prefs.setGridColumns(currentSpanCount) }
            }
        })

        var dragSelectActive = false
        var initialX = 0f
        var initialY = 0f

        binding.recyclerView.addOnItemTouchListener(object : androidx.recyclerview.widget.RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: android.view.MotionEvent): Boolean {
                scaleDetector.onTouchEvent(e)
                if (e.pointerCount > 1 || scaleDetector.isInProgress) return true
                if (vm.selectedPhotos.value.isEmpty()) return false

                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        dragSelectActive = false
                        initialX = e.x
                        initialY = e.y
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!dragSelectActive) {
                            val dx = Math.abs(e.x - initialX)
                            val dy = Math.abs(e.y - initialY)
                            if (dx > 20f && dx > dy) {
                                dragSelectActive = true
                            }
                        }

                        if (dragSelectActive) {
                            handleDragSelect(rv, e)
                            return true
                        }
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: android.view.MotionEvent) {
                scaleDetector.onTouchEvent(e)
                if (dragSelectActive && e.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
                    handleDragSelect(rv, e)
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

            private fun handleDragSelect(rv: androidx.recyclerview.widget.RecyclerView, e: android.view.MotionEvent) {
                val child = rv.findChildViewUnder(e.x, e.y)
                if (child != null) {
                    val position = rv.getChildAdapterPosition(child)
                    if (position != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        val item = adapter.currentList.getOrNull(position)
                        if (item is GridItem.Photo) {
                            vm.setSelection(item.photo.uri.toString(), true)
                        }
                    }
                }
                if (e.y < 200f) rv.scrollBy(0, -40)
                else if (e.y > rv.height - 200f) rv.scrollBy(0, 40)
            }
        })

        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {

            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                updateFastScroller()
                if (dy == 0) return

                val firstVisiblePosition = gridLayoutManager.findFirstVisibleItemPosition()
                if (firstVisiblePosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    val item = adapter.currentList.getOrNull(firstVisiblePosition)
                    val dateStr = when (item) {
                        is GridItem.Header -> item.title
                        is GridItem.Photo -> {
                            val format = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
                            format.format(java.util.Date(item.photo.dateAdded * 1000L))
                        }
                        else -> null
                    }
                    if (dateStr != null) {
                        binding.tvFastScrollDate.text = dateStr
                        binding.tvFastScrollDate.visibility = View.VISIBLE

                        val dateView = binding.tvFastScrollDate
                        hideDateRunnable?.let { dateView.removeCallbacks(it) }
                        val hide = Runnable { dateView.visibility = View.GONE }
                        hideDateRunnable = hide
                        dateView.postDelayed(hide, 1000)
                    }
                }
            }
        })

        binding.btnAccount.setOnClickListener {
            AccountBottomSheet().show(parentFragmentManager, AccountBottomSheet.TAG)
        }

        binding.btnCloseCab.setOnClickListener {
            vm.clearSelection()
        }

        binding.btnBackupNow.setOnClickListener {
            vm.backupSelected()
            android.widget.Toast.makeText(requireContext(), "Backup started", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { com.photosapp.di.ServiceLocator.prefs.gridColumns.collect { columns ->
                    currentSpanCount = columns
                    (binding.recyclerView.layoutManager as? GridLayoutManager)?.spanCount = columns
                } }
                launch { com.photosapp.di.ServiceLocator.prefs.showBackupBadges.collect { show ->
                    adapter.showBackupBadges = show
                    binding.recyclerView.post { if (_binding != null) adapter.notifyItemRangeChanged(0, adapter.itemCount, "backup") }
                } }
                launch { kotlinx.coroutines.flow.combine(vm.accountList, com.photosapp.di.ServiceLocator.prefs.googleEmail) { accounts, email -> accounts.firstOrNull { it.email == email } ?: accounts.firstOrNull() }.collect { account ->
                    binding.btnAccount.load(account?.photoUrl ?: R.drawable.ic_account_circle) { transformations(coil.transform.CircleCropTransformation()); error(R.drawable.ic_account_circle) }
                } }
                launch {
                    vm.gridItems.collect { items ->
                        adapter.submitList(items) { updateFastScroller() }
                        binding.tvEmpty.visibility =
                            if (items.isEmpty()) View.VISIBLE else View.GONE
                        binding.tvEmpty.setText(R.string.backup_empty_filter)
                    }
                }
                launch { vm.cloudMessage.collect { binding.tvCloudStatus.text = it } }
                launch {
                    vm.backupSummary.collect { status ->
                        binding.backupTitle.text = status.title(requireContext())
                        binding.backupDetail.text = status.detail(requireContext())
                        val active = status.runtime.running && !status.runtime.paused
                        binding.backupProgress.isIndeterminate = active && status.currentProgress == null
                        if (!binding.backupProgress.isIndeterminate) {
                            val fraction = (status.backedUp + (status.currentProgress ?: 0) / 100.0) / status.total.coerceAtLeast(1)
                            binding.backupProgress.setProgressCompat((fraction * 1000).toInt().coerceIn(0, 1000), true)
                        }
                        binding.backupProgress.visibility = if (status.total > 0 || active) View.VISIBLE else View.GONE
                        binding.backupSummaryIcon.setImageResource(when {
                            status.failed > 0 && !active -> R.drawable.ic_backup_error
                            status.runtime.paused -> R.drawable.ic_backup_waiting
                            active -> R.drawable.ic_backup_upload
                            status.remaining == 0 && status.total > 0 && status.loaded -> R.drawable.ic_backup_done
                            else -> R.drawable.ic_backup_off
                        })
                        binding.backupAction.setText(when {
                            !status.hasAccounts -> R.string.backup_action_connect
                            status.runtime.paused -> R.string.backup_action_resume
                            active -> R.string.backup_action_pause
                            status.failed > 0 -> R.string.backup_action_retry
                            status.remaining > 0 -> R.string.backup_action_start
                            else -> R.string.backup_action_details
                        })
                        binding.backupAction.isEnabled = status.loaded
                    }
                }
                launch {
                    vm.selectedPhotos.collect { selected ->
                        val hasSelection = selected.isNotEmpty()
                        binding.cabToolbar.visibility = if (hasSelection) View.VISIBLE else View.GONE
                        binding.normalToolbar.visibility = if (hasSelection) View.GONE else View.VISIBLE
                        binding.tvCabTitle.text = "${selected.size} selected"

                        // Force adapter rebind to update UI checkmarks
                        // A simple approach for now is to just notify dataset changed
                        val recycler = binding.recyclerView
                        recycler.post {
                            if (_binding?.recyclerView === recycler) adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }
    }

    private fun showBackupDetails() {
        val state = vm.backupSummary.value
        val errors=vm.photos.value.filter { it.syncState==SyncState.FAILED }.take(10).mapNotNull { photo -> photo.backupError?.let { photo.name+"\n"+it } }
        val details = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_details_title)
            .setMessage(listOf(state.title(requireContext()),
                getString(R.string.backup_detail_counts, state.backedUp, state.uploading, state.waiting, state.notBackedUp, state.failed),
                getString(R.string.backup_legend), getString(R.string.backup_details_scope),
                if(errors.isNotEmpty()) getString(R.string.backup_error_samples)+"\n"+errors.joinToString("\n\n") else "").filter { it.isNotBlank() }.joinToString("\n\n"))
            .setPositiveButton(android.R.string.ok, null)
        if(state.failed>0) details.setNeutralButton(R.string.backup_retry_failed) { _,_-> vm.retryFailed() }
        details.show()
    }

    private fun setupFastScroller() {
        val current = _binding ?: return
        current.recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateFastScroller() }
        current.fastScrollTrack.setOnTouchListener { track, event ->
            val views = _binding ?: return@setOnTouchListener false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                    if (adapter.itemCount == 0) return@setOnTouchListener false
                    draggingFastScroll = true
                    track.parent.requestDisallowInterceptTouchEvent(true)
                    views.recyclerView.stopScroll()
                    val travel = (track.height - views.fastScrollHandle.height).coerceAtLeast(1)
                    val top = (event.y - views.fastScrollHandle.height / 2f).coerceIn(0f, travel.toFloat())
                    views.fastScrollHandle.translationY = top
                    val position = ((top / travel) * (adapter.itemCount - 1)).toInt().coerceIn(0, adapter.itemCount - 1)
                    (views.recyclerView.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(position, 0)
                    val item = adapter.currentList.getOrNull(position)
                    val label = when (item) {
                        is GridItem.Header -> item.title
                        is GridItem.Photo -> java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(item.photo.dateAdded * 1000))
                        else -> ""
                    }
                    views.tvFastScrollDate.text = label
                    views.tvFastScrollDate.visibility = View.VISIBLE
                    hideDateRunnable?.let { views.tvFastScrollDate.removeCallbacks(it) }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    draggingFastScroll = false
                    track.parent.requestDisallowInterceptTouchEvent(false)
                    val bubble = views.tvFastScrollDate
                    val hide = Runnable { bubble.visibility = View.GONE }
                    hideDateRunnable = hide
                    bubble.postDelayed(hide, 800)
                    updateFastScroller()
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP) track.performClick()
                    true
                }
                else -> false
            }
        }
        current.fastScrollTrack.setOnKeyListener { _, key, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN || (key != android.view.KeyEvent.KEYCODE_DPAD_UP && key != android.view.KeyEvent.KEYCODE_DPAD_DOWN)) false
            else {
                current.recyclerView.scrollBy(0, current.recyclerView.height * if (key == android.view.KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                true
            }
        }
    }

    private fun updateFastScroller() {
        val views = _binding ?: return
        val recycler = views.recyclerView
        val scrollable = recycler.canScrollVertically(1) || recycler.canScrollVertically(-1)
        views.fastScrollTrack.visibility = if (scrollable) View.VISIBLE else View.GONE
        if (!scrollable || draggingFastScroll) return
        val manager = recycler.layoutManager as? GridLayoutManager ?: return
        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val visible = (manager.findLastVisibleItemPosition() - first + 1).coerceAtLeast(1)
        val fraction = if (!recycler.canScrollVertically(1)) 1f else (first.toFloat() / (adapter.itemCount - visible).coerceAtLeast(1)).coerceIn(0f, 1f)
        views.fastScrollHandle.translationY = fraction * (views.fastScrollTrack.height - views.fastScrollHandle.height).coerceAtLeast(0)
    }

    private fun requestPermissionsAndSchedule() {
        if (hasMediaAccess()) { scheduleBackup(); requestBackupNotifications() } else if (!requireActivity().getPreferences(0).getBoolean("media_requested", false)) {
            requireActivity().getPreferences(0).edit().putBoolean("media_requested", true).apply()
            requestMediaPermissions()
        }
    }

    private fun requestBackupNotifications() {
        if (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val prefs = requireActivity().getPreferences(0)
        if (prefs.getBoolean("backup_notifications_requested", false)) return
        prefs.edit().putBoolean("backup_notifications_requested", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasMediaAccess(): Boolean {
        fun granted(permission: String) = androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) ||
            (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun requestMediaPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.READ_MEDIA_VIDEO
            if (Build.VERSION.SDK_INT >= 34) perms += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        } else perms += Manifest.permission.READ_EXTERNAL_STORAGE
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun scheduleBackup() {
        viewLifecycleOwner.lifecycleScope.launch {
        if (com.photosapp.di.ServiceLocator.prefs.backupPaused.first()) return@launch
        val wifiOnly = com.photosapp.di.ServiceLocator.prefs.wifiOnly.first()
        WorkManager.getInstance(requireContext()).enqueueUniquePeriodicWork(
            BackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            BackupWorker.buildRequest(wifiOnly)
        )
        }
    }

    override fun onResume() {
        super.onResume()
        vm.loadPhotos()
        openingViewer = false
        fun granted(permission: String) = androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val fullAccess = if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
            else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        binding.btnMediaAccess.visibility = if (fullAccess || selectedTab == R.id.nav_albums) View.GONE else View.VISIBLE
        binding.btnMediaAccess.setText(if (hasMediaAccess()) R.string.backup_media_access_limited else R.string.backup_media_access_needed)
    }

    override fun onDestroyView() {
        draggingFastScroll = false
        hideDateRunnable?.let { binding.tvFastScrollDate.removeCallbacks(it) }
        hideDateRunnable = null
        binding.recyclerView.clearOnScrollListeners()
        binding.recyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
    private fun hasFullMediaAccess(): Boolean {
        fun granted(permission: String) = androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("tab", selectedTab); super.onSaveInstanceState(outState) }
}
