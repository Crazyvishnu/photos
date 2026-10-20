package com.photosapp.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.gms.auth.api.signin.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photosapp.R
import com.photosapp.data.local.db.GoogleAccount
import com.photosapp.databinding.DialogAccountBottomSheetBinding
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountBottomSheet : BottomSheetDialogFragment() {
    private var views: DialogAccountBottomSheetBinding? = null
    private val vm: HomeViewModel by activityViewModels { HomeViewModel.Factory }
    private var expanded = true
    private var pendingAccount: GoogleSignInAccount? = null
    private var backupTitle: TextView? = null
    private var backupDetail: TextView? = null
    private var backupButton: MaterialButton? = null
    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        try {
            pendingAccount = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(com.google.android.gms.common.api.ApiException::class.java)
            linkPendingAccount()
        } catch (error: Exception) { toast("Unable to link this account. Try again.") }
    }
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) linkPendingAccount() else toast("Drive access is needed for backup.")
    }
    private val archive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                androidx.work.WorkManager.getInstance(requireContext()).enqueue(androidx.work.OneTimeWorkRequestBuilder<com.photosapp.worker.ImportWorker>()
                    .setInputData(androidx.work.workDataOf(com.photosapp.worker.ImportWorker.KEY_ZIP_URI to uri.toString())).build())
                toast("Archive import started")
            } catch (error: Exception) { toast("Couldn't open that archive. Please select it again.") }
        }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        DialogAccountBottomSheetBinding.inflate(inflater, container, false).also { views = it }.root

    override fun onViewCreated(view: View, state: Bundle?) {
        vm.loadStatus()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(vm.accountList, ServiceLocator.prefs.googleEmail, ServiceLocator.prefs.localMode) { accounts, email, local -> Triple(accounts, email, local) }
                        .collect { (accounts, email, local) -> render(accounts, email, local) }
                }
                launch { vm.backupSummary.collect { summary -> updateBackup(summary) } }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).apply {
            maxHeight = (resources.displayMetrics.heightPixels * 0.94).toInt()
            isFitToContents = true; skipCollapsed = true; state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }
    private fun render(accounts: List<GoogleAccount>, email: String?, local: Boolean) {
        val binding = views ?: return
        val context = binding.root.context
        val root = binding.accountContent
        root.removeAllViews()
        val close = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Close"; contentDescription = "Close account center"; setOnClickListener { dismiss() }
        }
        root.addView(close, LinearLayout.LayoutParams(-2, root.dp(48)).apply { gravity = Gravity.END })
        val active = accounts.firstOrNull { it.email == email } ?: accounts.firstOrNull()
        val profile = card(root)
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(20), dp(12), dp(20)) }
        header.addView(avatar(if (local) null else active, 64), LinearLayout.LayoutParams(root.dp(64), root.dp(64)))
        val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(4), 0) }
        names.addView(label(if (local) "Use without an account" else active?.displayName ?: active?.email ?: "Your photo library", 22))
        names.addView(label(if (local) "Only photos and videos on this device" else active?.email ?: "Link an account to start backing up", 14, secondary = true))
        header.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        val expand = androidx.appcompat.widget.AppCompatImageButton(context).apply {
            setImageResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more); contentDescription = if (expanded) "Collapse account list" else "Expand account list"
            minimumWidth = dp(48); setPadding(dp(12),dp(12),dp(12),dp(12)); setBackgroundColor(android.graphics.Color.TRANSPARENT); imageTintList = android.content.res.ColorStateList.valueOf(context.color(R.color.accent))
            setOnClickListener { expanded = !expanded; render(accounts, email, local) }
        }
        header.addView(expand, LinearLayout.LayoutParams(root.dp(48), root.dp(48)))
        profile.addView(header)
        if (expanded) {
            accounts.forEach { account ->
                divider(profile)
                val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(88); setPadding(dp(16), dp(10), dp(8), dp(10)) }
                line.addView(avatar(account, 40), LinearLayout.LayoutParams(root.dp(40), root.dp(40)))
                val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
                texts.addView(label(account.displayName ?: account.email, 16))
                if (!account.displayName.isNullOrBlank()) texts.addView(label(account.email, 13, true))
                texts.addView(label(storageText(account.storageUsage, account.totalStorageLimit), 12, true))
                line.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                line.setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { ServiceLocator.prefs.saveAuth("", "", account.email) } }
                line.contentDescription = "Use ${account.email}"
                val remove = androidx.appcompat.widget.AppCompatImageButton(context).apply {
                    setImageResource(R.drawable.ic_view_more); contentDescription = "Options for ${account.email}"; minimumWidth = dp(48); setPadding(dp(12),dp(12),dp(12),dp(12)); setBackgroundColor(android.graphics.Color.TRANSPARENT); imageTintList = android.content.res.ColorStateList.valueOf(context.color(R.color.accent))
                    setOnClickListener {
                        MaterialAlertDialogBuilder(context).setTitle(account.email)
                            .setItems(arrayOf("Manage Google Account", "Unlink from Photos App")) { _, index ->
                                if (index == 0) googleLink("https://myaccount.google.com/", account.email) else unlink(account)
                            }.show()
                    }
                }
                line.addView(remove, LinearLayout.LayoutParams(root.dp(48), root.dp(48))); profile.addView(line)
            }
            divider(profile)
            action(profile, "Use Photos without an account") {
                vm.stopBackup()
                viewLifecycleOwner.lifecycleScope.launch { ServiceLocator.prefs.setLocalMode(true) }
            }
            divider(profile)
            action(profile, "Add another account") { launchSignIn() }
            divider(profile)
            action(profile, "Manage accounts on this device") { launch(Intent(android.provider.Settings.ACTION_SYNC_SETTINGS)) }
        }
        val manage = card(root)
        action(manage, "Manage your Google Account") { googleLink("https://myaccount.google.com/", if (local) null else active?.email) }
        val backup = card(root)
        backupTitle = label("", 16).apply { setPadding(dp(20), dp(16), dp(20), 0) }.also { backup.addView(it) }
        backupDetail = label("", 13, true).apply { setPadding(dp(20), dp(6), dp(20), dp(8)) }.also { backup.addView(it) }
        backupButton = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setOnClickListener {
                if (accounts.isEmpty()) launchSignIn()
                else if (vm.backupSummary.value.runtime.running) vm.stopBackup() else vm.backupAll()
            }
        }.also { backup.addView(it, LinearLayout.LayoutParams(-1, root.dp(52))) }
        updateBackup(vm.backupSummary.value)
        val storage = card(root)
        val total = if (accounts.isNotEmpty() && accounts.all { it.totalStorageLimit != null }) accounts.sumOf { it.totalStorageLimit ?: 0 } else null
        val usage = accounts.sumOf { it.storageUsage }
        storage.addView(label(if (accounts.isEmpty()) "Link an account for cloud storage" else storageText(usage, total), 18).apply { setPadding(dp(20), dp(20), dp(20), dp(12)) })
        val progress = com.google.android.material.progressindicator.LinearProgressIndicator(context).apply {
            max = 1000; setProgressCompat(if (total != null && total > 0) (usage.toDouble() / total * 1000).toInt().coerceIn(0,1000) else 0, false)
        }
        storage.addView(progress, LinearLayout.LayoutParams(-1, root.dp(4)).apply { setMargins(root.dp(20), 0, root.dp(20), root.dp(12)) })
        storage.addView(label("Across ${accounts.size} linked accounts · shared with Gmail and Drive", 12, true).apply { setPadding(dp(20), 0, dp(20), dp(12)) })
        action(storage, "Manage Google storage") { googleLink("https://one.google.com/storage", active?.email) }
        val settings = card(root)
        action(settings, "Photos settings") {
            val manager = requireActivity().supportFragmentManager
            if (!manager.isStateSaved) { manager.beginTransaction().replace(R.id.fragment_container, SettingsFragment()).addToBackStack(null).commit(); dismiss() }
        }
        action(settings, "Import a photo archive (ZIP)") { archive.launch(arrayOf("application/zip", "application/x-zip-compressed")) }
        action(settings, "Refresh cloud library") { vm.refreshCloud(); vm.loadStatus(); toast("Refreshing cloud library") }
    }
    private fun updateBackup(summary: BackupSummary) {
        backupTitle?.text = summary.title(requireContext())
        backupDetail?.text = summary.detail(requireContext())
        backupButton?.text = when { !summary.hasAccounts -> "Link an account"; summary.runtime.running -> "Pause backup"; summary.runtime.paused -> "Resume backup"; else -> "Back up now" }
    }
    private fun unlink(account: GoogleAccount) {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Unlink ${account.email}?")
            .setMessage("Backups already stored in this Google Drive account stay there. They will disappear from this app until you link the account again.")
            .setNegativeButton("Cancel", null).setPositiveButton("Unlink") { _, _ ->
                val context = requireContext().applicationContext
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    ServiceLocator.db.accountDao().deleteAccount(account.email)
                    ServiceLocator.forgetAccount(account.email)
                    ServiceLocator.db.cloudMediaDao().deleteAccount(account.email)
                    ServiceLocator.db.libraryStateDao().deleteCursor(account.email)
                    if (ServiceLocator.prefs.googleEmail.first() == account.email) {
                        val next = ServiceLocator.db.accountDao().getAllAccounts().firstOrNull()
                        if (next != null) ServiceLocator.prefs.saveAuth("", "", next.email)
                        else { ServiceLocator.prefs.setLocalMode(true); ServiceLocator.prefs.setBackupPaused(true); ServiceLocator.prefs.clearAuth() }
                    }
                    GoogleSignIn.getClient(context, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
                }
            }.show()
    }
    private fun launchSignIn() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestProfile()
            .requestScopes(com.google.android.gms.common.api.Scope(ServiceLocator.DRIVE_SCOPE)).build()
        val client = GoogleSignIn.getClient(requireContext(), options)
        client.signOut().addOnCompleteListener { if (isAdded && views != null) signIn.launch(client.signInIntent) }
    }
    private fun linkPendingAccount() {
        val account = pendingAccount ?: return
        val email = account.email ?: return
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.google.android.gms.auth.GoogleAuthUtil.getToken(context, android.accounts.Account(email, "com.google"), "oauth2:${ServiceLocator.DRIVE_SCOPE}")
                    val dao = ServiceLocator.db.accountDao()
                    dao.insert((dao.getAccount(email) ?: GoogleAccount(email)).copy(displayName = account.displayName, photoUrl = account.photoUrl?.toString()))
                    ServiceLocator.prefs.saveAuth("", "", email)
                }
                pendingAccount = null
                vm.loadStatus(); vm.refreshCloud(); toast("Account linked")
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: com.google.android.gms.auth.UserRecoverableAuthException) { consent.launch(error.intent) }
            catch (error: Exception) { toast("Unable to link account. Check Drive access and try again.") }
        }
    }
    private fun avatar(account: GoogleAccount?, size: Int): View {
        val context = requireContext()
        val frame = FrameLayout(context)
        val initials = TextView(context).apply {
            text = (account?.displayName?.firstOrNull() ?: account?.email?.firstOrNull())?.uppercase() ?: "?"
            gravity = Gravity.CENTER; textSize = size * 0.45f; setTextColor(context.color(R.color.accent))
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(context.color(R.color.accent_light)); setStroke(dp(2), context.color(R.color.accent)) }
        }
        frame.addView(initials, FrameLayout.LayoutParams(-1,-1))
        if (account?.photoUrl != null) frame.addView(ImageView(context).apply {
            load(account.photoUrl) { transformations(CircleCropTransformation()); listener(onError = { _, _ -> visibility = View.GONE }) }
        }, FrameLayout.LayoutParams(-1,-1))
        return frame
    }
    private fun card(root: LinearLayout): LinearLayout {
        val card = MaterialCardView(requireContext()).apply { radius = dp(28).toFloat(); strokeWidth = 0; cardElevation = 0f; setCardBackgroundColor(context.color(R.color.card_surface)) }
        val content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        card.addView(content); root.addView(card, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = root.dp(12) })
        return content
    }
    private fun action(parent: LinearLayout, title: String, click: () -> Unit) {
        parent.addView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = title; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)); setTextColor(context.color(R.color.text_primary))
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(-1,-2).apply { height = ViewGroup.LayoutParams.WRAP_CONTENT })
    }
    private fun label(value: String, size: Int, secondary: Boolean = false) = TextView(requireContext()).apply {
        text = value; textSize = size.toFloat(); setTextColor(context.color(if (secondary) R.color.text_secondary else R.color.text_primary))
    }
    private fun divider(parent: LinearLayout) { parent.addView(View(requireContext()).apply { setBackgroundColor(context.color(R.color.surface)) }, LinearLayout.LayoutParams(-1, parent.dp(1))) }
    private fun storageText(used: Long, total: Long?) = if (total == null) "%.2f GB used · limit unavailable".format(used / 1e9) else "%.2f GB of %.0f GB used".format(used / 1e9, total / 1e9)
    private fun googleLink(url: String, email: String?) { launch(Intent(Intent.ACTION_VIEW, Uri.parse(url).buildUpon().apply { if (email != null) appendQueryParameter("authuser", email) }.build())) }
    private fun launch(intent: Intent) { try { startActivity(intent) } catch (error: android.content.ActivityNotFoundException) { toast("No app is available to open this link.") } }
    private fun toast(message: String) { if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show() }
    override fun onDestroyView() { views = null; backupTitle = null; backupDetail = null; backupButton = null; super.onDestroyView() }
    companion object { const val TAG = "AccountBottomSheet" }
}
