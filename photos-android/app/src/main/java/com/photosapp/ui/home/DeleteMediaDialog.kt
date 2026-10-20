package com.photosapp.ui.home
import android.app.Dialog
import android.os.Bundle
import android.net.Uri
import android.os.Build
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photosapp.di.ServiceLocator
import com.photosapp.data.MediaStateRepository
import kotlinx.coroutines.*

class DeleteMediaDialog : DialogFragment() {
    private val vm: HomeViewModel by activityViewModels { HomeViewModel.Factory }
    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if(result.resultCode == android.app.Activity.RESULT_OK) perform() else dismiss()
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if(allowed) perform() else dismiss() }
    override fun onCreateDialog(state: Bundle?): Dialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle("Delete permanently?").setMessage("Selected device files and app cloud backups will be permanently deleted. This cannot be undone. Android may ask you to approve device deletion.")
        .setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_-> }.create().also { dialog ->
            dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener { dialog.getButton(-1).isEnabled=false; perform() } }
        }
    private fun perform() {
        lifecycleScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { requireArguments().getStringArrayList("keys").orEmpty().mapNotNull { ServiceLocator.db.libraryStateDao().trash(it) } }
                val context = requireContext().applicationContext
                val device = withContext(Dispatchers.IO) { entries.flatMap { MediaStateRepository.localUris(it) }.distinct().filter {
                    ServiceLocator.db.sharedMediaDao().get(it)==null && Uri.parse(it).authority=="media" &&
                        runCatching { context.contentResolver.query(Uri.parse(it),arrayOf(android.provider.MediaStore.MediaColumns._ID),null,null,null)?.use { c -> c.moveToFirst() } == true }.getOrDefault(true)
                } }
                if(device.isNotEmpty()) {
                    if(Build.VERSION.SDK_INT>=30) {
                        launcher.launch(IntentSenderRequest.Builder(android.provider.MediaStore.createDeleteRequest(context.contentResolver,device.take(1000).map { Uri.parse(it) }).intentSender).build()); return@launch
                    }
                    if(Build.VERSION.SDK_INT<=28 && androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE); return@launch
                    }
                    try { withContext(Dispatchers.IO) { device.forEach { requireContext().contentResolver.delete(Uri.parse(it),null,null) } } }
                    catch(e: android.app.RecoverableSecurityException) { if(Build.VERSION.SDK_INT>=29) { launcher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()); return@launch } else throw e }
                }
                MediaStateRepository().purge(entries); vm.loadPhotos(); dismiss()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(isAdded) { android.widget.Toast.makeText(requireContext(), e.message ?: "Could not delete",android.widget.Toast.LENGTH_LONG).show(); dismiss() } }
        }
    }
    companion object { fun newInstance(keys: List<String>) = DeleteMediaDialog().apply { arguments=Bundle().apply { putStringArrayList("keys",ArrayList(keys)) } } }
}
