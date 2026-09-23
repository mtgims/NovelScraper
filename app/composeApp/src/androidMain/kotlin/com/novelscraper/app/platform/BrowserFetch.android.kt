package com.novelscraper.app.platform

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.novelscraper.app.extensions.BrowserCookieJar
import com.novelscraper.app.extensions.Extensions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Android has a WebView. */
actual val canFetchThroughBrowser: Boolean = true

private val lock = Mutex()
private const val LOAD_MS = 45_000L

/** Loads the page in an offscreen WebView, which the site sees as a browser,
 *  and returns the document it ends up with. */
@SuppressLint("SetJavaScriptEnabled")
actual suspend fun fetchThroughBrowser(url: String): String? = lock.withLock {
    withContext(Dispatchers.Main) {
        val view = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = browserUserAgent
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        try {
            val loaded = CompletableDeferred<Unit>()
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, u: String) { loaded.complete(Unit) }
            }
            view.loadUrl(url)
            withTimeoutOrNull(LOAD_MS) {
                loaded.await()
                delay(SETTLE_MS)
                var page = html(view)
                // The site may answer with its check first and swap in the real
                // page once the browser has run it, which takes a few seconds.
                while (page != null && looksLikeBrowserCheck(page)) {
                    delay(1_500)
                    page = html(view)
                }
                // The cookies that got the page through go to the extensions, so
                // the pages after it can be fetched plainly.
                if (page != null) keepCookies(url)
                page
            }
        } finally {
            view.destroy()
        }
    }
}

private const val SETTLE_MS = 2_500L

/** Give what the WebView collected to the extensions' cookie jar. The header form
 *  is name/value pairs only, so the domain is the one we asked for. */
private fun keepCookies(url: String) {
    val http = url.toHttpUrlOrNull() ?: return
    val header = CookieManager.getInstance().getCookie(url).orEmpty()
    val cookies = header.split(';').mapNotNull { pair ->
        val at = pair.indexOf('=')
        if (at <= 0) return@mapNotNull null
        BrowserCookieJar.BrowserCookie(
            name = pair.take(at).trim(),
            value = pair.substring(at + 1).trim(),
            domain = http.host,
            path = "/",
            expiresAt = 0L,
            secure = http.isHttps,
            httpOnly = false,
        )
    }
    if (cookies.isEmpty()) return
    Extensions.cookies.acceptFromBrowser(http, cookies)
}

/** The page as it stands, unescaped from the JavaScript string the WebView returns. */
private suspend fun html(view: WebView): String? {
    val result = CompletableDeferred<String?>()
    view.evaluateJavascript("document.documentElement.outerHTML") { value ->
        result.complete(value?.takeIf { it.isNotBlank() && it != "null" })
    }
    val raw = withTimeoutOrNull(10_000) { result.await() } ?: return null
    return org.json.JSONTokener(raw).nextValue() as? String
}
