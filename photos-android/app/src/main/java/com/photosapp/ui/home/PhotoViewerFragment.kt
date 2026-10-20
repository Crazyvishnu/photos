package com.photosapp.ui.home

import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.Toast
import android.widget.SeekBar
import androidx.core.view.doOnLayout
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photosapp.R
import com.photosapp.data.MediaStateRepository
import com.photosapp.data.MediaTransferRepository
import com.photosapp.databinding.FragmentPhotoViewerBinding
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PhotoViewerFragment : Fragment() {
    private var _binding: FragmentPhotoViewerBinding? = null
    private val vm: HomeViewModel by activityViewModels { HomeViewModel.Factory }
    private var adapter: FullscreenPhotoAdapter? = null
    private var pageCallback: ViewPager2.OnPageChangeCallback? = null
    private var selectedUri: String? = null
    private var applyingList = false
    private var busy = false
    private var exportUri: Uri? = null
    private var editUri: Uri? = null
    private var editDate: Long = 0
    private var favoriteMenu: MenuItem? = null
    private var scrubbing = false
    var isImmersive: Boolean = false
        private set
    private val download = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val source=exportUri; exportUri=null
        if(target!=null && source!=null) lifecycleScope.launch {
            try {
                val context=requireContext().applicationContext
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(source)).use { input ->
                        requireNotNull(context.contentResolver.openOutputStream(target)).use { input.copyTo(it,128*1024) }
                    }
                }
                message("Saved to your selected folder")
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { message(e.message ?: "Could not save") }
        }
    }
    private val externalEdit = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val edited=result.data?.data ?: editUri
        editUri=null
        if(result.resultCode==android.app.Activity.RESULT_OK && edited!=null) lifecycleScope.launch {
            try {
                MediaTransferRepository().importEdited(edited,editDate)
                vm.loadPhotos(); message("Edited copy saved")
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { message(e.message ?: "Could not import edited copy") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isImmersive=savedInstanceState?.getBoolean("immersive") ?: false
        selectedUri=savedInstanceState?.getString(ARG_URI) ?: arguments?.getString(ARG_URI)
        exportUri=savedInstanceState?.getString("export")?.let(Uri::parse)
        editUri=savedInstanceState?.getString("edit")?.let(Uri::parse)
        editDate=savedInstanceState?.getLong("editDate") ?: 0
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding=FragmentPhotoViewerBinding.inflate(inflater,container,false); _binding=binding; return binding.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding=requireNotNull(_binding)
        val pages=FullscreenPhotoAdapter(viewLifecycleOwner.lifecycleScope) { setImmersive(!isImmersive) }; adapter=pages
        binding.viewPager.adapter=pages
        val callback=object: ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pages.stopPlayback()
                if(!applyingList) pages.currentList.getOrNull(position)?.let { selectedUri=it.uri.toString() }
                scrubbing = false
                renderBackupStatus()
            }
        }
        pageCallback=callback; binding.viewPager.registerOnPageChangeCallback(callback)
        binding.toolbar.setNavigationOnClickListener { if(!parentFragmentManager.isStateSaved) parentFragmentManager.popBackStack() }
        binding.toolbar.navigationIcon?.setTint(requireContext().color(R.color.viewer_ink))
        binding.toolbar.overflowIcon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_view_more)
        binding.photoBackupStatus.setOnClickListener { showDetails() }
        binding.showDetails.setOnClickListener { showDetails() }
        binding.dateHeader.setOnClickListener { currentPhoto()?.let { editDate(it) } }
        binding.fullscreenMedia.setOnClickListener { setImmersive(true) }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (isImmersive) setImmersive(false)
            else if (!parentFragmentManager.isStateSaved) parentFragmentManager.popBackStack()
        }
        setImmersive(isImmersive)
        binding.shareMedia.setOnClickListener { currentPhoto()?.let { MediaUiActions.share(this,listOf(it)) } }
        binding.editMedia.setOnClickListener { currentPhoto()?.let { edit(it) } }
        binding.addToAlbum.setOnClickListener { currentPhoto()?.let { AlbumActions.choose(this,vm,setOf(it.uri.toString())) } }
        binding.binMedia.setOnClickListener { currentPhoto()?.let { MediaUiActions.bin(this,vm,listOf(it)) } }
        binding.muteVideo.setOnClickListener {
            val muted=pages.toggleMute()
            if(muted==null) message("Play the video first") else renderPlayback()
        }
        binding.playPause.setOnClickListener { currentPhoto()?.let { pages.togglePlayback(it.uri) }; renderPlayback() }
        binding.videoSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { scrubbing = true }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val photo = currentPhoto() ?: return
                val state = pages.playback(photo.uri)
                binding.playbackTime.text = getString(R.string.viewer_playback_time,
                    playbackTime(state.duration * progress / seekBar.max), playbackTime(state.duration))
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                currentPhoto()?.let { pages.seekTo(it.uri, seekBar.progress.toFloat() / seekBar.max) }
                scrubbing = false
                renderPlayback()
            }
        })
        // A view-scoped clock keeps app controls in sync with playback, buffering and frame capture.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) { if (!isImmersive) renderPlayback(); delay(250) }
            }
        }
        binding.saveFrame.setOnClickListener {
            if(busy) return@setOnClickListener
            val photo=currentPhoto() ?: return@setOnClickListener
            val frame=pages.frame()
            if(frame==null) { message("Play or pause the video at the frame to save"); return@setOnClickListener }
            busy=true
            lifecycleScope.launch {
                try { MediaTransferRepository().saveBitmap(frame,photo); vm.loadPhotos(); message("Frame saved as photo with the video's date") }
                catch(e: CancellationException) { throw e }
                catch(e: Exception) { message(e.message ?: "Could not save frame") }
                finally { frame.recycle(); busy=false }
            }
        }
        favoriteMenu=binding.toolbar.menu.add("Add to favorites").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                currentPhoto()?.let { photo -> lifecycleScope.launch {
                    try { MediaStateRepository().favorite(photo,!photo.isFavorite) }
                    catch(e: CancellationException) { throw e }
                    catch(e: Exception) { message("Could not update favorite") }
                } }; true
            }
        }
        fun menu(label: String, action: (LocalPhoto)->Unit) {
            binding.toolbar.menu.add(label).setOnMenuItemClickListener { currentPhoto()?.let(action); true }
        }
        menu("Details") { showDetails() }
        menu("Edit date & time") { editDate(it) }
        menu("Save to device") { photo ->
            if(!busy) {
                busy=true; message("Preparing file…")
                lifecycleScope.launch {
                    try { exportUri=MediaTransferRepository().export(photo); if(isAdded) download.launch(photo.name) }
                    catch(e: CancellationException) { throw e }
                    catch(e: Exception) { message(e.message ?: "Could not download") }
                    finally { busy=false }
                }
            }
        }
        menu("Move to Bin") { MediaUiActions.bin(this,vm,listOf(it)) }
        binding.toolbar.menu.add("Open Bin").setOnMenuItemClickListener { TrashFragment.open(this); true }
        val initialIndex=arguments?.getInt(ARG_INDEX) ?: 0
        var firstLoad=true
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(vm.photos,vm.albums) { all,albums ->
                    val album=arguments?.getString("album_id")
                    if(album==null) all else albums.firstOrNull { it.id==album }?.photos.orEmpty()
                }.collect { photos ->
                    val anchor=selectedUri; applyingList=true
                    pages.submitList(photos) {
                        val current=_binding?.takeIf { it.viewPager.adapter===pages } ?: return@submitList
                        if(photos.isNotEmpty()) {
                            val index=photos.indexOfFirst { it.uri.toString()==anchor }.takeIf { it>=0 }
                                ?: (if(firstLoad) initialIndex else current.viewPager.currentItem).coerceIn(0,photos.lastIndex)
                            if(current.viewPager.scrollState==ViewPager2.SCROLL_STATE_IDLE) {
                                if(index!=current.viewPager.currentItem) current.viewPager.setCurrentItem(index,false)
                                selectedUri=photos[index].uri.toString()
                            }
                            firstLoad=false
                        } else if(!firstLoad && !parentFragmentManager.isStateSaved) parentFragmentManager.popBackStack()
                        applyingList=false; renderBackupStatus()
                    }
                }
            }
        }
        if(vm.photos.value.isEmpty()) { vm.loadPhotos(); vm.refreshCloud() }
    }
    private fun currentPhoto(): LocalPhoto? = _binding?.let { adapter?.currentList?.getOrNull(it.viewPager.currentItem) }
    private fun editDate(photo: LocalPhoto) {
        if(!parentFragmentManager.isStateSaved && parentFragmentManager.findFragmentByTag(EditDateTimeDialog.TAG)==null)
            EditDateTimeDialog.newInstance(photo).show(parentFragmentManager,EditDateTimeDialog.TAG)
    }
    private fun edit(photo: LocalPhoto) {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Edit").setItems(if(photo.isVideo) arrayOf("Edit date & time","Open video editor") else arrayOf("Rotate / crop photo","Edit date & time")) { _,which ->
            if((photo.isVideo && which==0) || (!photo.isVideo && which==1)) editDate(photo)
            else if(!photo.isVideo) PhotoEditDialog.newInstance(photo).show(parentFragmentManager,"photo_editor")
            else if(!busy) {
                busy=true; adapter?.stopPlayback(); message("Preparing video for editor…")
                lifecycleScope.launch {
                    try {
                        val uri=MediaTransferRepository().export(photo); editUri=uri; editDate=photo.dateAdded
                        val intent=Intent(Intent.ACTION_EDIT).setDataAndType(uri,"video/*")
                            .putExtra("output",uri).putExtra("return-data",true)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        intent.clipData=ClipData.newUri(requireContext().contentResolver,"Edit video copy",uri)
                        externalEdit.launch(intent)
                    } catch(e: android.content.ActivityNotFoundException) { message("No video editor installed. Install an editor to trim or edit videos.") }
                    catch(e: CancellationException) { throw e }
                    catch(e: Exception) { message(e.message ?: "Could not open editor") }
                    finally { busy=false }
                }
            }
        }.show()
    }
    private fun showDetails() {
        val photo=currentPhoto() ?: return
        if(!parentFragmentManager.isStateSaved && parentFragmentManager.findFragmentByTag(MediaDetailsBottomSheet.TAG)==null)
            MediaDetailsBottomSheet.newInstance(photo.uri.toString()).show(parentFragmentManager,MediaDetailsBottomSheet.TAG)
    }
    private fun renderBackupStatus() {
        val binding=_binding ?: return
        val photo=currentPhoto() ?: return
        binding.photoBackupStatus.text=photo.backupLabel(binding.root.context)
        binding.photoBackupStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(photo.backupIcon(),0,0,0)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(binding.photoBackupStatus,android.content.res.ColorStateList.valueOf(requireContext().color(R.color.viewer_ink)))
        val date=Instant.ofEpochSecond(photo.dateAdded).atZone(ZoneId.systemDefault())
        binding.viewerDate.text=date.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
        binding.viewerTime.text=android.text.format.DateFormat.getTimeFormat(requireContext()).format(java.util.Date(photo.dateAdded*1000))
        binding.videoControls.visibility=if(photo.isVideo) View.VISIBLE else View.GONE
        renderPlayback()
        binding.statusRow.visibility=if(photo.syncState==com.photosapp.data.local.db.SyncState.SUCCESS) View.GONE else View.VISIBLE
        binding.bottomChrome.doOnLayout { if (_binding === binding) adapter?.setAppearance(isImmersive, it.height + binding.root.dp(8)) }
        favoriteMenu?.apply {
            title=if(photo.isFavorite) "Remove from favorites" else "Add to favorites"
            setIcon(if(photo.isFavorite) R.drawable.ic_view_star else R.drawable.ic_view_star_outline)
            icon?.setTint(requireContext().color(R.color.viewer_ink))
        }
    }
    private fun renderPlayback() {
        val binding = _binding ?: return
        val photo = currentPhoto()?.takeIf { it.isVideo } ?: return
        val state = adapter?.playback(photo.uri) ?: return
        binding.playPause.isEnabled = !state.starting
        binding.playPause.setImageResource(if (state.playing) R.drawable.ic_view_pause else R.drawable.ic_view_play)
        binding.playPause.contentDescription = getString(if (state.playing) R.string.viewer_pause else R.string.viewer_play)
        binding.muteVideo.setIconResource(if (state.muted) R.drawable.ic_view_volume_off else R.drawable.ic_view_volume)
        binding.muteVideo.contentDescription = getString(if (state.muted) R.string.viewer_unmute else R.string.viewer_mute)
        binding.videoSeek.isEnabled = state.duration > 0
        if (!scrubbing) {
            binding.videoSeek.progress = if (state.duration > 0)
                (state.position.toDouble() / state.duration * binding.videoSeek.max).toInt().coerceIn(0, binding.videoSeek.max) else 0
            binding.playbackTime.text = getString(R.string.viewer_playback_time, playbackTime(state.position),
                if (state.duration > 0) playbackTime(state.duration) else "--:--")
        }
    }
    private fun playbackTime(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(java.util.Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
    }
    private fun setImmersive(hidden: Boolean) {
        isImmersive = hidden
        val binding = _binding ?: return
        binding.root.setBackgroundColor(if(hidden) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        binding.topChrome.visibility = if(hidden) View.GONE else View.VISIBLE
        binding.bottomChrome.visibility = if(hidden) View.GONE else View.VISIBLE
        adapter?.setAppearance(hidden)
        if (!hidden) renderPlayback()
        (activity as? com.photosapp.ui.MainActivity)?.setViewerMode(true, hidden)
    }
    override fun onResume() { super.onResume(); setImmersive(isImmersive) }
    private fun message(text: String) { if(isAdded) Toast.makeText(requireContext(),text,Toast.LENGTH_LONG).show() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("immersive",isImmersive); outState.putString(ARG_URI,selectedUri); outState.putString("export",exportUri?.toString()); outState.putString("edit",editUri?.toString()); outState.putLong("editDate",editDate)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { adapter?.stopPlayback(); super.onPause() }
    override fun onDestroyView() {
        pageCallback?.let { _binding?.viewPager?.unregisterOnPageChangeCallback(it) }; pageCallback=null
        adapter?.release(); _binding?.viewPager?.adapter=null; adapter=null; applyingList=false; favoriteMenu=null; _binding=null
        (activity as? com.photosapp.ui.MainActivity)?.setViewerMode(false)
        super.onDestroyView()
    }
    companion object {
        private const val ARG_INDEX="arg_index"; private const val ARG_URI="arg_uri"
        fun newInstance(index: Int,uri: String,albumId: String?=null)=PhotoViewerFragment().apply {
            arguments=Bundle().apply { putInt(ARG_INDEX,index); putString(ARG_URI,uri); putString("album_id",albumId) }
        }
    }
}
