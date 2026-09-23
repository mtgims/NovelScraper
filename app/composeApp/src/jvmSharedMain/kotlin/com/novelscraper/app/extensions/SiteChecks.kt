package com.novelscraper.app.extensions

import com.novelscraper.app.platform.canPassSiteChecks
import com.novelscraper.app.platform.passSiteCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sites that asked for a browser check, and the app's answer to them.
 *
 * A plugin's request coming back as a check ([SiteChallengeException]) records
 * the address here; the app then offers to open it in a real browser (a WebView
 * on the phone, Chromium on the desktop). What the browser collects goes to the
 * extensions' cookie jar, so the next request goes through like any other.
 */
object SiteChecks {

    private val _pending = MutableStateFlow<String?>(null)
    /** The address of a site waiting for a check, if any. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    /** What the check is doing right now (null when it isn't running). */
    val status: StateFlow<String?> = _status.asStateFlow()

    val possible: Boolean get() = canPassSiteChecks

    /** A source answered with a browser check. */
    fun needed(url: String) {
        if (_status.value == null) _pending.value = url
    }

    fun dismiss() {
        _pending.value = null
    }

    /** Open the browser and wait for the site to let us through. */
    suspend fun run(): Boolean {
        val url = _pending.value ?: return false
        _status.value = "Starting…"
        return try {
            val ok = passSiteCheck(url) { _status.value = it }
            if (ok) _pending.value = null
            ok
        } finally {
            _status.value = null
        }
    }
}
