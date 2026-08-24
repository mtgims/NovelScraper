package com.novelscraper.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads a NovelUpdates series page in an OFFSCREEN WebView, using the login cookies
 * the user established in the in-app NU browser (WebView cookies are app-global), so
 * pasting an NU link into the normal scraper "just works" without popping a visible
 * browser. Returns empty/null when it can't — not logged in, or a Cloudflare
 * challenge that needs interaction — and the caller falls back to the visible
 * browser. Serialized (one operation at a time), reuses a single WebView.
 */
object NuResolver {
    private const val TIMEOUT_MS = 20_000L
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    @Volatile private var appContext: Context? = null
    @Volatile private var webView: WebView? = null

    fun init(context: Context) { appContext = context.applicationContext }

    /** Groups on the series page, or empty if the page couldn't be read logged-in. */
    suspend fun extractGroups(seriesUrl: String): List<NuGroup> = mutex.withLock {
        val ctx = appContext ?: return emptyList()
        withContext(Dispatchers.Main) {
            val wv = ensureWebView(ctx)
            wv.stopLoading(); wv.loadUrl(seriesUrl)
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(500)
                val gs = NuExtract.parse(evalJs(wv, NuExtract.EXTRACT_JS))
                if (gs.isNotEmpty()) return@withContext gs
            }
            emptyList()
        }
    }

    /** Follow a group's /extnu/ link to the translator's URL, or null on failure. */
    suspend fun resolveExtnu(extnu: String): String? = mutex.withLock {
        val ctx = appContext ?: return null
        withContext(Dispatchers.Main) {
            val wv = ensureWebView(ctx)
            wv.stopLoading(); wv.loadUrl(extnu)
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(400)
                val cur = decode(evalJs(wv, "location.href"))
                if (cur.isNotBlank() && cur != "about:blank" && !NuExtract.isNovelUpdatesHost(cur)) {
                    delay(300)  // let the TL page settle before reading its final URL
                    return@withContext decode(evalJs(wv, "location.href")).ifBlank { cur }
                }
            }
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(ctx: Context): WebView =
        webView ?: WebView(ctx).also { w ->
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = false
            }
            webView = w
        }

    private suspend fun evalJs(wv: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(script) { v ->
                if (cont.isActive) cont.resumeWith(Result.success(v ?: "null"))
            }
        }

    private fun decode(evalResult: String): String =
        runCatching { json.parseToJsonElement(evalResult).jsonPrimitive.content }.getOrDefault("")
}
