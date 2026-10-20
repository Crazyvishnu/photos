package com.photosapp.ui.home

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photosapp.data.MediaStateRepository
import com.photosapp.data.MediaTransferRepository
import kotlinx.coroutines.*

object MediaUiActions {
    fun bin(fragment: Fragment, vm: HomeViewModel, photos: List<LocalPhoto>) {
        if (photos.isEmpty()) return
        MaterialAlertDialogBuilder(fragment.requireContext()).setTitle("Move ${photos.size} item(s) to Bin?")
            .setMessage("Hide these items from Photos App and move their app cloud backups to Drive Bin. Device originals stay on the phone until you delete them permanently from Bin.")
            .setNegativeButton("Cancel", null).setPositiveButton("Move to Bin") { _, _ ->
                fragment.lifecycleScope.launch {
                    try {
                        MediaStateRepository().moveToBin(photos)
                        vm.clearSelection()
                        if (fragment.isAdded) Toast.makeText(fragment.requireContext(), "Moved to Bin · open Collections → Bin to restore", Toast.LENGTH_LONG).show()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if(fragment.isAdded) Toast.makeText(fragment.requireContext(), e.message ?: "Could not move to Bin", Toast.LENGTH_LONG).show() }
                }
            }.show()
    }
    fun share(fragment: Fragment, photos: List<LocalPhoto>) {
        if(photos.isEmpty()) return
        Toast.makeText(fragment.requireContext(),"Preparing to share…",Toast.LENGTH_SHORT).show()
        fragment.lifecycleScope.launch {
            try {
                val uris = photos.map { MediaTransferRepository().export(it) }
                if(!fragment.isAdded) return@launch
                val mime = if(photos.all { it.isVideo }) "video/*" else if(photos.none { it.isVideo }) "image/*" else "*/*"
                val intent=Intent(if(uris.size==1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                    type=mime
                    if(uris.size==1) putExtra(Intent.EXTRA_STREAM,uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(uris))
                    clipData=ClipData.newUri(fragment.requireContext().contentResolver,"Media",uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                fragment.startActivity(Intent.createChooser(intent,"Share"))
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(fragment.isAdded) Toast.makeText(fragment.requireContext(),e.message ?: "Could not share",Toast.LENGTH_LONG).show() }
        }
    }
}
