package com.roadlog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadlog.data.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SignInViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun signIn(email: String, password: String) = runAuthAction {
        authRepository.signInWithEmail(email, password)
    }

    fun signUp(email: String, password: String) = runAuthAction {
        authRepository.signUpWithEmail(email, password)
    }

    fun signInWithGoogleIdToken(idToken: String) = runAuthAction {
        authRepository.signInWithGoogleIdToken(idToken)
    }

    // Credential Manager's account-picker step happens in the Composable
    // (it needs an Activity Context, which a ViewModel shouldn't hold), so
    // it can't go through runAuthAction like the calls above — these let it
    // report into the same loading/error state instead of failing silently.
    fun beginGoogleSignIn() {
        _isLoading.value = true
        _errorMessage.value = null
    }

    fun reportGoogleSignInFailure(message: String) {
        _errorMessage.value = message
        _isLoading.value = false
    }

    /** User backed out of the account picker — not a failure, just stop showing the spinner. */
    fun cancelGoogleSignIn() {
        _isLoading.value = false
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun runAuthAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                block()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Something went wrong"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
