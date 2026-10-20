package com.photosapp.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.photosapp.data.local.db.SyncState
import com.photosapp.databinding.ItemPhotoBinding

class PhotoGridAdapter(
    private val onClick: (LocalPhoto) -> Unit,
    private val onLongClick: (LocalPhoto) -> Unit,
    private val isSelectionModeActive: () -> Boolean,
    private val isSelected: (LocalPhoto) -> Boolean
) : ListAdapter<GridItem, RecyclerView.ViewHolder>(DiffCallback) {
    var showBackupBadges: Boolean = true

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PHOTO = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is GridItem.Header -> TYPE_HEADER
            is GridItem.Photo -> TYPE_PHOTO
        }
    }

    inner class PhotoViewHolder(private val binding: ItemPhotoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GridItem.Photo) {
            val photo = item.photo
            binding.root.setOnClickListener {
                if (isSelectionModeActive()) {
                    onLongClick(photo)
                } else {
                    onClick(photo)
                }
            }
            binding.root.setOnLongClickListener {
                onLongClick(photo)
                true
            }

            val selected = isSelected(photo)
            binding.selectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
            binding.selectionCheck.visibility = if (selected) View.VISIBLE else View.GONE

            val padding = if (selected) (8 * binding.root.resources.displayMetrics.density).toInt() else 0
            binding.imageView.setPadding(padding, padding, padding, padding)

            binding.imageView.contentDescription = photo.name
            binding.imageView.load(photo.thumbnailLink ?: photo.uri) {
                photo.accountEmail?.let { setHeader("X-Photos-Account", it) }
                crossfade(true)
                placeholder(com.photosapp.R.drawable.placeholder_photo)
                error(com.photosapp.R.drawable.placeholder_photo)
                transformations(RoundedCornersTransformation(if (selected) 16f else 0f))
            }

            bindBackup(photo)

            binding.videoIndicator.visibility = if (photo.isVideo) View.VISIBLE else View.GONE
        }

        fun bindBackup(photo: LocalPhoto) {
            binding.backupBadge.visibility = if (showBackupBadges) View.VISIBLE else View.GONE
            val context = binding.root.context
            val label = photo.backupLabel(context)
            val tint = androidx.core.content.ContextCompat.getColor(context, photo.backupTint())
            val surface = when (photo.syncState) {
                SyncState.SUCCESS -> com.photosapp.R.color.backup_success_surface
                SyncState.UPLOADING -> com.photosapp.R.color.accent_light
                SyncState.PENDING -> com.photosapp.R.color.backup_waiting_surface
                SyncState.FAILED -> com.photosapp.R.color.backup_error_surface
                SyncState.IDLE -> com.photosapp.R.color.backup_idle_surface
            }
            binding.backupBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(context, surface))
            binding.backupLabel.text = if (photo.syncState == SyncState.UPLOADING && photo.backupPhase == com.photosapp.worker.BackupWorker.PHASE_UPLOADING)
                context.getString(com.photosapp.R.string.backup_progress_short, photo.uploadProgress ?: 0) else label
            binding.backupLabel.setTextColor(tint)
            binding.backupIndicator.setImageResource(photo.backupIcon())
            binding.backupIndicator.imageTintList = android.content.res.ColorStateList.valueOf(tint)
            binding.backupSpinner.visibility = if (photo.syncState == SyncState.UPLOADING) View.VISIBLE else View.GONE
            binding.backupIndicator.visibility = if (photo.syncState == SyncState.UPLOADING) View.GONE else View.VISIBLE
            binding.root.contentDescription = context.getString(com.photosapp.R.string.backup_photo_description, photo.name, label)
            binding.imageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvDate: android.widget.TextView = view.findViewById(com.photosapp.R.id.tv_date)
        fun bind(item: GridItem.Header) {
            tvDate.text = item.title
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_PHOTO) {
            PhotoViewHolder(ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            val view = LayoutInflater.from(parent.context).inflate(com.photosapp.R.layout.item_date_header, parent, false)
            HeaderViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is PhotoViewHolder && item is GridItem.Photo) {
            holder.bind(item)
        } else if (holder is HeaderViewHolder && item is GridItem.Header) {
            holder.bind(item)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val item = getItem(position)
        if (payloads.isNotEmpty() && holder is PhotoViewHolder && item is GridItem.Photo) holder.bindBackup(item.photo)
        else super.onBindViewHolder(holder, position, payloads)
    }

    object DiffCallback : DiffUtil.ItemCallback<GridItem>() {
        override fun areItemsTheSame(a: GridItem, b: GridItem): Boolean {
            return when {
                a is GridItem.Photo && b is GridItem.Photo -> a.photo.uri == b.photo.uri
                a is GridItem.Header && b is GridItem.Header -> a.id == b.id
                else -> false
            }
        }
        override fun areContentsTheSame(a: GridItem, b: GridItem): Boolean {
            return a == b
        }
        override fun getChangePayload(oldItem: GridItem, newItem: GridItem): Any? {
            if (oldItem is GridItem.Photo && newItem is GridItem.Photo) {
                val next = newItem.photo
                if (oldItem.photo.copy(syncState = next.syncState, uploadProgress = next.uploadProgress,
                        backupPhase = next.backupPhase, backupError = next.backupError) == next) return "backup"
            }
            return null
        }
    }
}
