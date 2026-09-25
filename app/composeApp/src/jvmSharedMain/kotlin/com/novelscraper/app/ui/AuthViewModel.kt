package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.LoginRequest
import com.novelscraper.app.data.RegisterRequest
import com.novelscraper.app.data.UserRead
import com.novelscraper.app.library.Library
import com.novelscraper.app.net.Account
import com.novelscraper.app.net.Net
import com.novelscraper.app.net.detail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** The sign-in and registration screens (reached from Settings). */
class AuthViewModel : ViewModel() {

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val baseUrl: String get() = Net.baseUrl

    fun clearError() { _error.value = null }

    fun setBaseUrl(url: String) {
        Net.setBaseUrl(url)
        Account.serverChanged()
        _error.value = null
    }

    fun login(username: String, password: String, onDone: () -> Unit) =
        signIn(onDone, "Sign-in") { Net.api.login(LoginRequest(username.trim(), password)) }

    private val _openSignup = MutableStateFlow(false)
    val openSignup: StateFlow<Boolean> = _openSignup.asStateFlow()

    /** Whether open signup is enabled (else an invite code is required). Fetched
     *  lazily for the register screen. */
    fun loadAuthConfig() {
        viewModelScope.launch {
            try { _openSignup.value = Net.api.authConfig().allow_open_signup } catch (_: Exception) {}
        }
    }

    fun register(username: String, password: String, invite: String, onDone: () -> Unit) =
        signIn(onDone, "Sign-up") {
            Net.api.register(RegisterRequest(username.trim(), password, invite.trim().ifBlank { null }))
        }

    private fun signIn(onDone: () -> Unit, what: String, call: suspend () -> UserRead) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                Account.signedIn(call())
                onDone()
            } catch (e: HttpException) {
                _error.value = e.detail() ?: "$what failed (${e.code()})"
            } catch (e: Exception) {
                _error.value = "Can't reach the server. Check the address and your connection."
            } finally {
                _busy.value = false
            }
        }
    }
}
