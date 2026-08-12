package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.LoginRequest
import com.novelscraper.app.data.RegisterRequest
import com.novelscraper.app.data.UserRead
import com.novelscraper.app.net.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import retrofit2.HttpException

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val user: UserRead) : AuthState
}

class AuthViewModel : ViewModel() {

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val baseUrl: String get() = Net.baseUrl

    init { checkSession() }

    /** Resume an existing session (persisted cookie), else land on sign-in. */
    fun checkSession() {
        _state.value = AuthState.Loading
        viewModelScope.launch {
            _state.value = try {
                AuthState.SignedIn(Net.api.me())
            } catch (e: Exception) {
                AuthState.SignedOut
            }
        }
    }

    fun clearError() { _error.value = null }

    fun setBaseUrl(url: String) {
        Net.setBaseUrl(url)
        _error.value = null
        checkSession()
    }

    fun login(username: String, password: String) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val user = Net.api.login(LoginRequest(username.trim(), password))
                _state.value = AuthState.SignedIn(user)
            } catch (e: HttpException) {
                _error.value = detailOf(e) ?: "Sign-in failed (${e.code()})"
            } catch (e: Exception) {
                _error.value = "Can't reach the server. Check the address and your connection."
            } finally {
                _busy.value = false
            }
        }
    }

    private val _openSignup = MutableStateFlow(false)
    val openSignup: StateFlow<Boolean> = _openSignup.asStateFlow()

    /** Whether open signup is enabled (else an invite code is required). Fetched
     *  lazily for the register screen. */
    fun loadAuthConfig() {
        viewModelScope.launch {
            try { _openSignup.value = Net.api.authConfig().allow_open_signup } catch (_: Exception) {}
        }
    }

    fun register(username: String, password: String, invite: String) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val user = Net.api.register(
                    RegisterRequest(username.trim(), password, invite.trim().ifBlank { null }),
                )
                _state.value = AuthState.SignedIn(user)
            } catch (e: HttpException) {
                _error.value = detailOf(e) ?: "Sign-up failed (${e.code()})"
            } catch (e: Exception) {
                _error.value = "Can't reach the server. Check the address and your connection."
            } finally {
                _busy.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try { Net.api.logout() } catch (_: Exception) {}
            Net.cookieJar.clear()
            _state.value = AuthState.SignedOut
        }
    }

    /** Pull the FastAPI `{"detail": "..."}` message out of an error response. */
    private fun detailOf(e: HttpException): String? = try {
        e.response()?.errorBody()?.string()?.let { JSONObject(it).optString("detail").ifBlank { null } }
    } catch (_: Exception) { null }
}
