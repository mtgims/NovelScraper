package com.novelscraper.app.net

import com.novelscraper.app.data.UserRead
import com.novelscraper.app.library.Library
import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.settingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException

/**
 * The NovelScraper server account, which is optional: the library, Browse and the
 * reader work without one. Signed in, the server's library is imported and kept
 * in step, and the server-side tools (scraping by URL, EPUB import, stats) work.
 *
 * A saved session counts as signed in while the server can't be reached, so the
 * app opens offline straight into the library; only the server saying the
 * session is gone (401) signs out.
 */
object Account {

    sealed interface State {
        data object SignedOut : State
        /** [online]: the server confirmed the session in this run. */
        data class SignedIn(val username: String, val online: Boolean) : State
    }

    private lateinit var prefs: KeyValueStore
    private val _state = MutableStateFlow<State>(State.SignedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    val signedIn: Boolean get() = _state.value is State.SignedIn

    fun init() {
        prefs = settingsStore("ns")
        val name = prefs.getString(KEY_USER, null)
        val session = Net.baseUrl.toHttpUrlOrNull()?.let { Net.cookieJar.hasCookieFor(it) } == true
        _state.value = if (session) State.SignedIn(name.orEmpty(), online = false) else State.SignedOut
    }

    /** Ask the server whether the saved session is still good. Offline, nothing
     *  changes. True when signed in and online. */
    suspend fun check(): Boolean {
        if (_state.value is State.SignedOut) return false
        return try {
            signedIn(Net.api.me())
            true
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) forget()
            false
        } catch (e: Exception) {
            false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The app came to the foreground (or started, on desktop). Signed in: keep
     * the scrape relay up (scrapes then go through this device's connection),
     * ask for due server updates, confirm the session, then bring in what is new
     * on the server and send changes made here.
     */
    fun onForeground() {
        if (!signedIn) return
        ScrapeRelay.start()
        AutoUpdate.trigger()
        scope.launch { if (check()) Library.pullServerSoon() }
    }

    /** A sign-in or registration succeeded. */
    fun signedIn(user: UserRead) {
        prefs.putString(KEY_USER, user.username)
        _state.value = State.SignedIn(user.username, online = true)
        ScrapeRelay.start()
    }

    suspend fun logout() {
        try { Net.api.logout() } catch (_: Exception) {}
        forget()
    }

    /** The server address changed: a session there is a different account. */
    fun serverChanged() {
        val session = Net.baseUrl.toHttpUrlOrNull()?.let { Net.cookieJar.hasCookieFor(it) } == true
        if (!session) forget()
    }

    private fun forget() {
        ScrapeRelay.stop()
        Net.cookieJar.clear()
        prefs.remove(KEY_USER)
        _state.value = State.SignedOut
    }

    private const val KEY_USER = "account_user"
}
