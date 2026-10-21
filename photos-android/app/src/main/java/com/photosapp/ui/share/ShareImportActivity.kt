package com.photosapp.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.photosapp.R
import com.photosapp.databinding.ActivityShareImportBinding
import kotlinx.coroutines.launch

/** Keep this activity alive while copying: share URI access is often temporary. */
class ShareImportActivity : AppCompatActivity() {
    private val vm: ShareImportViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            com.photosapp.di.ServiceLocator.prefs.theme.collect { theme ->
                val mode = when (theme) { "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO; "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES; else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }
                if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() != mode) androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
        val binding = ActivityShareImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val pad = (24 * resources.displayMetrics.density).toInt()
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        if (!vm.state.value.started) acceptShare(intent)
        binding.shareOpen.setOnClickListener {
            startActivity(Intent(this, com.photosapp.ui.MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP) })
            finish()
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.state.collect { state ->
                binding.shareCount.text = getString(R.string.share_counts, state.received, state.total, state.failed)
                binding.shareProgress.visibility = if (state.complete) View.GONE else View.VISIBLE
                binding.shareOpen.visibility = if (state.complete) View.VISIBLE else View.GONE
                binding.shareTitle.setText(if (!state.complete) R.string.share_receiving else if (state.received == 0) R.string.share_failed else R.string.share_received)
                binding.shareMessage.setText(when {
                    !state.complete -> R.string.share_receiving_detail
                    state.received == 0 -> R.string.share_retry_detail
                    state.queueFailed -> R.string.share_queue_failed
                    state.needsAccount -> R.string.share_connect_detail
                    else -> R.string.share_backup_queued
                })
            }
        } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
    }
    private fun acceptShare(intent: Intent) {
        val incoming = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            if (intent.action == Intent.ACTION_SEND) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { incoming += it }
            else incoming += IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { incoming += it } }
        }
        vm.receive(incoming.filter { it.scheme == "content" }.distinct())
    }

}
