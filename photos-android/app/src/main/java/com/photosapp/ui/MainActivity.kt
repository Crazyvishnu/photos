package com.photosapp.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.photosapp.databinding.ActivityMainBinding
import com.photosapp.ui.connect.ConnectFragment
import com.photosapp.ui.home.HomeFragment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle

class MainActivity : AppCompatActivity() {

    private var viewerImmersive = false
    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            com.photosapp.di.ServiceLocator.prefs.theme.collect { theme ->
                val mode = when (theme) { "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO; "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES; else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }
                if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() != mode) androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, if (viewerImmersive) 0 else bars.top, bars.right, if (viewerImmersive) 0 else maxOf(bars.bottom, keyboard.bottom))
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(manager: androidx.fragment.app.FragmentManager, fragment: androidx.fragment.app.Fragment) {
                if (fragment is androidx.fragment.app.DialogFragment) return
                if (fragment is com.photosapp.ui.home.PhotoViewerFragment) setViewerMode(true, fragment.isImmersive)
                else setViewerMode(false)

            }
        }, false)

        var authChecked = false
        splash.setKeepOnScreenCondition { !authChecked }

        lifecycleScope.launch {
            var previous: Boolean? = null
            repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.isLoggedIn.collect { loggedIn ->
                authChecked = true
                if ((previous == null && savedInstanceState == null) || (previous != null && previous != loggedIn)) {
                    supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    showFragment(if (loggedIn) HomeFragment() else ConnectFragment())
                }
                previous = loggedIn
            }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun setViewerMode(active: Boolean, immersive: Boolean = false) {
        viewerImmersive = active && immersive
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = if (active) !immersive else !dark
        controller.isAppearanceLightNavigationBars = if (active) !immersive else !dark
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (viewerImmersive) controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        else controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = !active
        binding.root.setBackgroundColor(if (active) {
            if (immersive) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        } else getColor(com.photosapp.R.color.background))
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
    }

    private fun showFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(binding.fragmentContainer.id, fragment)
            .commit()
    }
}
