package com.photosapp.ui.home

import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.photosapp.R
import com.photosapp.data.local.db.SyncState
import com.photosapp.databinding.DialogMediaDetailsBinding
import kotlinx.coroutines.launch
import java.util.Date

class MediaDetailsBottomSheet : BottomSheetDialogFragment() {
    private var _binding: DialogMediaDetailsBinding? = null
    private val vm: HomeViewModel by activityViewModels { HomeViewModel.Factory }
    private var currentPhoto: LocalPhoto? = null
    private var lastState: SyncState? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = DialogMediaDetailsBinding.inflate(inflater, container, false)
        _binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = requireNotNull(_binding)
        binding.closeDetails.setOnClickListener { dismiss() }
        binding.editDateTime.setOnClickListener {
            currentPhoto?.let { photo ->
                if (!parentFragmentManager.isStateSaved && parentFragmentManager.findFragmentByTag(EditDateTimeDialog.TAG) == null)
                    EditDateTimeDialog.newInstance(photo).show(parentFragmentManager, EditDateTimeDialog.TAG)
            }
        }
        binding.mediaBackupAction.setOnClickListener {
            currentPhoto?.let { vm.backupSelected(listOf(it.uri.toString()), retryNow = true) }
        }
        val uri = arguments?.getString(ARG_URI) ?: return
        val repository = MediaDetailsRepository(requireContext().applicationContext)
        var requested = false
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.photos.collect { photos ->
                    val photo = photos.firstOrNull { it.uri.toString() == uri } ?: return@collect
                    currentPhoto = photo
                    renderDate(photo)
                    renderBackup(photo)
                    if (!requested) {
                        requested = true
                        binding.mediaName.text = photo.name
                        // This child job lives until the view is destroyed, including stop/start transitions.
                        viewLifecycleOwner.lifecycleScope.launch {
                            val details = repository.read(photo)
                            if (_binding === binding) renderMetadata(details)
                        }
                    }
                }
            }
        }
        if (vm.photos.value.isEmpty()) { vm.loadPhotos(); vm.refreshCloud() }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            maxHeight = (resources.displayMetrics.heightPixels * 0.85).toInt()
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun renderMetadata(details: MediaDetails) {
        val binding = _binding ?: return
        val context = binding.root.context
        currentPhoto?.let { renderDate(it) }
        binding.mediaName.text = details.name
        val info = mutableListOf<String>()
        details.sizeBytes?.let { info += Formatter.formatFileSize(context, it) }
        details.mimeType?.let { info += it }
        if (details.width != null && details.height != null) info += getString(R.string.media_resolution, details.width, details.height)
        details.durationMillis?.let { duration ->
            val seconds = (duration / 1000).coerceAtLeast(0)
            val time = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
                else "%d:%02d".format(seconds / 60, seconds % 60)
            info += getString(R.string.media_duration, time)
        }
        binding.mediaFileInfo.text = info.joinToString("\n")
        binding.mediaFolder.text = listOfNotNull(getString(if (currentPhoto?.accountEmail != null) R.string.media_in_cloud else R.string.media_on_device), details.folder).joinToString("\n")
        binding.mediaCamera.visibility = if (details.camera.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.mediaCamera.text = details.camera
        binding.mediaMetadataNotice.visibility = if (details.unavailable) View.VISIBLE else View.GONE
        binding.mediaMetadataNotice.setText(R.string.media_metadata_unavailable)
        binding.mediaBackupAccount.text = details.account ?: getString(R.string.media_no_backup_account)
    }

    private fun renderBackup(photo: LocalPhoto) {
        val binding = _binding ?: return
        val previous = lastState
        lastState = photo.syncState
        if (photo.syncState == SyncState.SUCCESS && previous != null && previous != SyncState.SUCCESS) {
            viewLifecycleOwner.lifecycleScope.launch {
                val owner = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    photo.accountEmail ?: com.photosapp.di.ServiceLocator.db.syncDao().getStatus(photo.uri.toString())?.accountEmail
                }
                if (_binding === binding && owner != null) binding.mediaBackupAccount.text = owner
            }
        }
        binding.mediaBackupStatus.text = photo.backupLabel(binding.root.context)
        binding.mediaBackupStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(photo.backupIcon(), 0, 0, 0)
        val tint = androidx.core.content.ContextCompat.getColor(binding.root.context, photo.backupTint())
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(binding.mediaBackupStatus, android.content.res.ColorStateList.valueOf(tint))
        binding.mediaBackupMessage.text = photo.backupError.takeIf { photo.syncState == SyncState.FAILED } ?: getString(when (photo.syncState) {
            SyncState.SUCCESS -> R.string.backup_photo_saved
            SyncState.IDLE -> R.string.backup_photo_not_saved
            SyncState.PENDING -> R.string.backup_photo_waiting
            SyncState.UPLOADING -> R.string.backup_photo_processing
            SyncState.FAILED -> R.string.backup_error_generic
        })
        binding.mediaBackupAction.visibility = if (photo.accountEmail == null && photo.syncState != SyncState.SUCCESS && photo.syncState != SyncState.UPLOADING) View.VISIBLE else View.GONE
    }

    private fun renderDate(photo: LocalPhoto) {
        val binding = _binding ?: return
        val date = Date(photo.dateAdded * 1000L)
        binding.mediaDate.text = DateFormat.getMediumDateFormat(binding.root.context).format(date)
        binding.mediaTime.text = DateFormat.getTimeFormat(binding.root.context).format(date)
        val message = when {
            photo.dateSource == "received" -> R.string.date_fallback
            photo.dateSource == "edited" && photo.dateSyncPending -> R.string.date_pending
            photo.dateSource == "edited" -> R.string.date_edited
            else -> null
        }
        binding.mediaDateNote.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let { binding.mediaDateNote.setText(it) }
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }

    companion object {
        const val TAG = "media_details"
        private const val ARG_URI = "uri"
        fun newInstance(uri: String) = MediaDetailsBottomSheet().apply { arguments = Bundle().apply { putString(ARG_URI, uri) } }
    }
}
