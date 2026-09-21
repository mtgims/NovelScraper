package com.novelscraper.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The scrape relay on Android (see [RelayClient]), with an offscreen WebView to
 * render JS-only / Cloudflare-gated pages. Connected only while the app is
 * foregrounded (see App). If it drops mid-scrape the server falls back to a
 * server-side fetch. Not a foreground service (yet).
 */
actual object ScrapeRelay {
    // A believable mobile-Chrome UA so the residential/mobile IP + UA look normal.
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"

    // Overall budget for a single WebView render (well under the server's relay
    // timeout): time for the page to load, run JS and clear a Cloudflare challenge.
    private const val RENDER_TIMEOUT_MS = 20_000L

    private val json = Json { ignoreUnknownKeys = true }

    // Offscreen WebView render (JS-only / hard sites): created lazily on the main
    // thread, reused, and serialized so only one render runs at a time.
    @Volatile private var appContext: Context? = null
    @Volatile private var webView: WebView? = null
    private val renderMutex = Mutex()

    private val client = RelayClient(UA) { url ->
        val ctx = appContext ?: error("no context")
        renderMutex.withLock { withContext(Dispatchers.Main) { renderOnMain(ctx, url) } }
    }

    /** Provide the application context so the offscreen WebView can be created. */
    fun init(context: Context) { appContext = context.applicationContext }

    actual val connected: StateFlow<Boolean> get() = client.connected
    actual fun start() = client.start()
    actual fun stop() = client.stop()

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderOnMain(ctx: Context, url: String): String {
        val wv = webView ?: WebView(ctx).also { w ->
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            w.settings.userAgentString = UA
            w.settings.loadsImagesAutomatically = false
            w.settings.blockNetworkImage = true
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView, r: android.webkit.WebResourceRequest,
                ): Boolean = false  // keep navigation inside the WebView
            }
            webView = w
        }
        wv.stopLoading()
        wv.loadUrl(url)
        val deadline = SystemClock.elapsedRealtime() + RENDER_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(500)
            val ready = evalJs(wv,
                "(document.readyState==='complete') && ((document.body?document.body.innerText.length:0)>200) && " +
                "!/just a moment|checking your browser|cf-chl|enable javascript and cookies/i" +
                ".test((document.title||'')+' '+(document.body?document.body.innerText.slice(0,300):''))")
            if (ready == "true") break
        }
        val res = evalJs(wv, "document.documentElement.outerHTML")
        return runCatching { json.parseToJsonElement(res).jsonPrimitive.contentOrNull ?: "" }.getOrDefault("")
    }

    private suspend fun evalJs(wv: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(script) { value ->
                if (cont.isActive) cont.resumeWith(Result.success(value ?: "null"))
            }
        }
}
