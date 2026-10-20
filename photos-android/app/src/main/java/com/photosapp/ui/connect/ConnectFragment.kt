package com.photosapp.ui.connect

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.photosapp.databinding.FragmentConnectBinding
import com.photosapp.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException

class ConnectFragment : Fragment() {

    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!

    // We use activityViewModels to communicate with MainActivity
    private val mainVm: MainViewModel by activityViewModels { MainViewModel.Factory }

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            handleSignInSuccess(account)
        } catch (e: ApiException) {
            binding.tvError.text = "Sign in failed (Error Code: ${e.statusCode})"
            binding.tvError.visibility = View.VISIBLE
            binding.progressBar.visibility = View.GONE
            binding.btnConnect.isEnabled = true
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnLocal.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                com.photosapp.di.ServiceLocator.prefs.setBackupPaused(true)
                com.photosapp.di.ServiceLocator.prefs.setLocalMode(true)
            }
        }

        binding.btnConnect.setOnClickListener {
            binding.btnConnect.isEnabled = false
            binding.progressBar.visibility = View.VISIBLE
            binding.tvError.visibility = View.GONE

            // Configure Google Sign In to request Drive scopes
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .requestScopes(Scope("https://www.googleapis.com/auth/drive.file"))
                .build()

            val client = GoogleSignIn.getClient(requireContext(), gso)
            // Always sign out first to ensure we don't use a stale session if something went wrong
            client.signOut().addOnCompleteListener {
                signInLauncher.launch(client.signInIntent)
            }
        }
    }

    private fun handleSignInSuccess(account: GoogleSignInAccount) {
        val email = account.email
        if (email == null) {
            binding.tvError.text = "Could not get email address"
            binding.tvError.visibility = View.VISIBLE
            binding.progressBar.visibility = View.GONE
            binding.btnConnect.isEnabled = true
            return
        }

        // We need to request the token once on a background thread to trigger any necessary permission dialogs
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val androidAccount = android.accounts.Account(email, "com.google")
                val scope = "oauth2:https://www.googleapis.com/auth/drive.file"
                // This call blocks and fetches the token, ensuring the user has granted the scope
                GoogleAuthUtil.getToken(appContext, androidAccount, scope)

                // Save to Room DB
                val dao = com.photosapp.di.ServiceLocator.db.accountDao()
                dao.insert((dao.getAccount(email) ?: com.photosapp.data.local.db.GoogleAccount(email = email))
                    .copy(displayName = account.displayName, photoUrl = account.photoUrl?.toString()))

                withContext(Dispatchers.Main) {
                    mainVm.setLoggedInNative(email)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: UserRecoverableAuthException) {
                // The user hasn't explicitly granted the Drive scope yet.
                // We must launch the intent Google provides to show the consent dialog.
                withContext(Dispatchers.Main) {
                    recoverableAuthLauncher.launch(e.intent)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvError.text = "Authorization failed: ${e.message}"
                    binding.tvError.visibility = View.VISIBLE
                    binding.progressBar.visibility = View.GONE
                    binding.btnConnect.isEnabled = true
                }
            }
        }
    }

    // Handles the result of the UserRecoverableAuthException intent (the consent screen)
    private val recoverableAuthLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // User granted permission! Try again.
            val account = GoogleSignIn.getLastSignedInAccount(requireContext())
            if (account != null) {
                handleSignInSuccess(account)
            } else {
                binding.tvError.text = "Lost Google Account state. Please try again."
                binding.tvError.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
                binding.btnConnect.isEnabled = true
            }
        } else {
            binding.tvError.text = "Permission to access Drive was denied."
            binding.tvError.visibility = View.VISIBLE
            binding.progressBar.visibility = View.GONE
            binding.btnConnect.isEnabled = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
