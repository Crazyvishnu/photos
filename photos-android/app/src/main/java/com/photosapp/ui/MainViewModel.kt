package com.photosapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photosapp.data.local.PrefsRepository
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    val prefs: PrefsRepository = ServiceLocator.prefs
) : ViewModel() {

    val isLoggedIn = prefs.isLoggedIn
    val googleEmail = prefs.googleEmail

    fun setLoggedInNative(email: String) {
        viewModelScope.launch {
            // We only need to save the email. ServiceLocator interceptor handles the token fetch using GoogleAuthUtil.
            prefs.saveAuth("", "", email)
        }
    }

    fun clearAuth() {
        viewModelScope.launch { prefs.clearAuth() }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel() as T
        }
    }
}
