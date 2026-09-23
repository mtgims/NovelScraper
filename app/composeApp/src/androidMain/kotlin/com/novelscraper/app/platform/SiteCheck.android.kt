package com.novelscraper.app.platform

import android.webkit.CookieManager
import android.webkit.WebView
import com.novelscraper.app.extensions.Extensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Android has a WebView, so it can run the site's check itself. */
actual val canPassSiteChecks: Boolean = true

private const val CLEARANCE = "cf_clearance"
private const val WAIT_MS = 45_000L

/**
 * Loads [url] in an offscreen WebView with JavaScript on, which is what a
 * Cloudflare check wants to see, and waits for the clearance cookie. Checks that
 * need a tap (a checkbox) can't be answered this way and time out.
 */
actual suspend fun passSiteCheck(url: String, onStatus: (String) -> Unit): Boolean {
    val http = url.toHttpUrlOrNull() ?: return false
    onStatus("Opening the site…")
    val manager = CookieManager.getInstance()
    val view = withContext(Dispatchers.Main) {
        WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = browserUserAgent
            manager.setAcceptCookie(true)
            manager.setAcceptThirdPartyCookies(this, true)
            loadUrl(url)
        }
    }
    try {
        val passed = withTimeoutOrNull(WAIT_MS) {
            while (true) {
                val raw = withContext(Dispatchers.Main) { manager.getCookie(url) }.orEmpty()
                if (raw.contains(CLEARANCE)) return@withTimeoutOrNull raw
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE") ""
        }
        if (passed.isNullOrBlank()) {
            onStatus("The check didn't pass.")
            return false
        }
        Extensions.cookies.acceptFromBrowser(http, parse(passed))
        onStatus("Done.")
        return true
    } finally {
        withContext(Dispatchers.Main) { view.destroy() }
    }
}

/** "a=1; b=2" as pairs. */
private fun parse(header: String): List<Pair<String, String>> =
    header.split(';').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
    }
