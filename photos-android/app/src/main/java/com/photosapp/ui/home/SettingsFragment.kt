package com.photosapp.ui.home

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.photosapp.databinding.FragmentSettingsBinding
import com.photosapp.di.ServiceLocator
import com.photosapp.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private var views: FragmentSettingsBinding? = null
    private val vm: HomeViewModel by activityViewModels { HomeViewModel.Factory }
    private val prefs get() = ServiceLocator.prefs
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        FragmentSettingsBinding.inflate(inflater, container, false).also { views = it }.root

    override fun onViewCreated(view: View, state: Bundle?) {
        val binding = requireNotNull(views)
        binding.toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        section("Backup")
        row(getString(R.string.backup_notifications_title), getString(R.string.backup_notifications_detail)) {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, requireContext().packageName))
        }
        row(getString(R.string.backup_retry_failed_title), getString(R.string.backup_retry_failed_detail)) {
            vm.retryFailed()
            Toast.makeText(requireContext(), R.string.backup_retry_requested, Toast.LENGTH_SHORT).show()
        }
        toggle("Automatic backup", "Keep backing up your selected device folders.", prefs.backupPaused, invert = true) { enabled ->
            if (!enabled) vm.stopBackup() else {
                prefs.setLocalMode(false); prefs.setBackupPaused(false); schedule()
            }
        }
        toggle("Use mobile data", "Back up photos and videos on mobile data as well as Wi-Fi.", prefs.wifiOnly, invert = true) { enabled ->
            prefs.setWifiOnly(!enabled); schedule()
        }
        val folderRow = row("Back up device folders", "Choose Camera or all photo and video folders.") {
            viewLifecycleOwner.lifecycleScope.launch {
                val current = prefs.backupAllFolders.first()
                MaterialAlertDialogBuilder(requireContext()).setTitle("Back up device folders")
                    .setSingleChoiceItems(arrayOf("Camera (DCIM)", "All photo and video folders"), if (current) 1 else 0) { dialog, which ->
                        viewLifecycleOwner.lifecycleScope.launch { prefs.setBackupAllFolders(which == 1); prefs.setBackupCameraRoll(true); schedule() }
                        dialog.dismiss()
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        watchDetail(folderRow, prefs.backupAllFolders) { if (it) "All photo and video folders" else "Camera (DCIM)" }
        section("Appearance")
        val themeRow = row("Theme", "System default, light, or dark") {
            viewLifecycleOwner.lifecycleScope.launch {
                val values = arrayOf("system", "light", "dark")
                MaterialAlertDialogBuilder(requireContext()).setTitle("Theme")
                    .setSingleChoiceItems(arrayOf("System default", "Light", "Dark"), values.indexOf(prefs.theme.first()).coerceAtLeast(0)) { dialog, which ->
                        viewLifecycleOwner.lifecycleScope.launch { prefs.setTheme(values[which]) }; dialog.dismiss()
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        watchDetail(themeRow, prefs.theme) { when(it) { "light" -> "Light"; "dark" -> "Dark"; else -> "System default" } }
        val gridRow = row("Photo grid", "Choose 2, 3, or 4 photos per row. You can also pinch the photo grid.") {
            viewLifecycleOwner.lifecycleScope.launch {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Photo grid")
                    .setSingleChoiceItems(arrayOf("Comfortable · 2 per row", "Default · 3 per row", "Compact · 4 per row"), prefs.gridColumns.first() - 2) { dialog, which ->
                        viewLifecycleOwner.lifecycleScope.launch { prefs.setGridColumns(which + 2) }; dialog.dismiss()
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        watchDetail(gridRow, prefs.gridColumns) { "$it photos per row · pinch the grid to adjust" }
        toggle("Backup badges", "Show backup status on each thumbnail. Details always show the status.", prefs.showBackupBadges) { prefs.setShowBackupBadges(it) }
        section("Collections")
        toggle("Automatic albums", "Group Screenshots, Documents and Videos as your library changes.", prefs.automaticAlbums) { prefs.setAutomaticAlbums(it) }
        row("Restore automatic albums", "Show automatic albums you previously hid.") {
            viewLifecycleOwner.lifecycleScope.launch { prefs.restoreAutomaticAlbums(); Toast.makeText(requireContext(), "Automatic albums restored", Toast.LENGTH_SHORT).show() }
        }
        section("Library and accounts")
        row("Account center", "Manage linked accounts, storage, and Google account settings.") { AccountBottomSheet().show(parentFragmentManager, AccountBottomSheet.TAG) }
        row("Refresh cloud library", "Check your linked accounts for photos backed up on another device.") { vm.refreshCloud(); vm.loadStatus(); Toast.makeText(requireContext(), "Refreshing cloud library", Toast.LENGTH_SHORT).show() }
        row("Photo access", "Change which photos and videos this app can access.") {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${requireContext().packageName}")))
        }
        section("About")
        row("Last crash report", "View or share the most recent app crash.") { showCrash() }
        row("Photos App ${com.photosapp.BuildConfig.VERSION_NAME}", "Original-quality Drive backups. Custom albums sync with Photos web when the owning accounts are connected.") {}
        row("Photos on the web", "Open your shared library in a browser.") {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://crazyvishnu.github.io/photos/")))
        }
    }
    private suspend fun schedule() {
        com.photosapp.worker.BackupScheduler.ensure(requireContext())
        if (prefs.backupPaused.first() || prefs.localMode.first()) return
        androidx.work.WorkManager.getInstance(requireContext()).enqueueUniquePeriodicWork(com.photosapp.worker.BackupWorker.WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE, com.photosapp.worker.BackupWorker.buildRequest(prefs.wifiOnly.first()))
    }
    private fun section(title: String) {
        val label = TextView(requireContext()).apply { text = title; textSize = 14f; setTextColor(context.color(R.color.accent)); setPadding(dp(12), dp(24), dp(12), dp(10)) }
        views?.settingsContainer?.addView(label)
    }
    private fun row(title: String, detail: String, click: () -> Unit): LinearLayout {
        val context = requireContext()
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(72); setPadding(dp(12), dp(12), dp(12), dp(12)); isFocusable = true; isClickable = true }
        val value = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true); line.setBackgroundResource(value.resourceId)
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply { this.text = title; textSize = 16f; setTextColor(context.color(R.color.text_primary)) })
        text.addView(TextView(context).apply { this.text = detail; textSize = 13f; setTextColor(context.color(R.color.text_secondary)); setPadding(0, dp(4), dp(8), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f)); line.setOnClickListener { click() }
        views?.settingsContainer?.addView(line)
        return line
    }
    private fun toggle(title: String, detail: String, flow: Flow<Boolean>, invert: Boolean = false, save: suspend (Boolean) -> Unit) {
        val line = row(title, detail) {}
        val toggle = SwitchMaterial(requireContext()).apply { contentDescription = title; isEnabled = false; minimumHeight = dp(48) }
        line.addView(toggle)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect { current ->
                    toggle.setOnCheckedChangeListener(null)
                    toggle.isChecked = if (invert) !current else current
                    toggle.isEnabled = true
                    toggle.setOnCheckedChangeListener { _, value -> viewLifecycleOwner.lifecycleScope.launch { save(value) } }
                    line.setOnClickListener { toggle.isChecked = !toggle.isChecked }
                }
            }
        }
    }
    private fun <T> watchDetail(row: LinearLayout, flow: Flow<T>, label: (T) -> String) {
        val detail = (row.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { detail.text = label(it) }
        } }
    }
    private fun showCrash() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.photosapp.CrashDiagnostics.read(context) }.getOrNull() }
            val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.crash_report_title).setMessage(report ?: getString(R.string.crash_report_empty)).setNegativeButton("Close", null)
            if (report != null) dialog.setPositiveButton(R.string.media_share) { _, _ ->
                startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, report) }, getString(R.string.crash_report_title)))
            }
            dialog.show()
        }
    }
    override fun onDestroyView() { views = null; super.onDestroyView() }
}
