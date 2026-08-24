package com.novelscraper.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Scrape relay: holds a WebSocket to the backend's /api/relay and performs the
 * HTTP fetches the server asks for — from THIS device's IP, so Cloudflare
 * datacenter-ASN blocks (which 403 the server) don't apply. The server still does
 * all enumeration/parsing/pacing; we only do the raw GET/POST and return the
 * status + headers + body.
 *
 * Connected only while the app is foregrounded (see App). If it drops mid-scrape
 * the server falls back to a server-side fetch. Not a foreground service (yet).
 */
object ScrapeRelay {
    private const val TAG = "ScrapeRelay"
    // A believable mobile-Chrome UA so the residential/mobile IP + UA look normal.
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"

    // Overall budget for a single WebView render (well under the server's relay
    // timeout): time for the page to load, run JS and clear a Cloudflare challenge.
    private const val RENDER_TIMEOUT_MS = 20_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var want = false

    // Offscreen WebView render (JS-only / hard sites): created lazily on the main
    // thread, reused, and serialized so only one render runs at a time.
    @Volatile private var appContext: Context? = null
    @Volatile private var webView: WebView? = null
    private val renderMutex = Mutex()

    /** Provide the application context so the offscreen WebView can be created. */
    fun init(context: Context) { appContext = context.applicationContext }

    // Target-site fetches: don't follow redirects (the server does that so every
    // hop is SSRF-checked). Reuses the shared client's pool; the cookie jar only
    // holds backend cookies, so nothing leaks to scraped sites.
    private val fetchClient by lazy {
        Net.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    fun start() {
        want = true
        open()
    }

    fun stop() {
        want = false
        ws?.close(1000, null)
        ws = null
        _connected.value = false
    }

    private fun wsUrl(): String? {
        val b = Net.baseUrl
        val scheme = when {
            b.startsWith("https://") -> "wss://" + b.removePrefix("https://")
            b.startsWith("http://") -> "ws://" + b.removePrefix("http://")
            else -> return null
        }
        return scheme.trimEnd('/') + "/api/relay"
    }

    @Synchronized
    private fun open() {
        if (ws != null) return
        val url = wsUrl() ?: return
        ws = Net.client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private fun reconnectSoon() {
        if (!want) return
        scope.launch { delay(3000); open() }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connected.value = true
            Log.i(TAG, "relay connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handle(webSocket, text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (ws === webSocket) { ws = null; _connected.value = false }
            reconnectSoon()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "relay failure: ${t.message}")
            if (ws === webSocket) { ws = null; _connected.value = false }
            reconnectSoon()
        }
    }

    private suspend fun handle(webSocket: WebSocket, text: String) {
        val req = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val idEl = req["id"] ?: return
        val id = runCatching { idEl.jsonPrimitive.int }.getOrNull() ?: return
        val url = req["url"]?.jsonPrimitive?.contentOrNull ?: return
        val method = req["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val headers = req["headers"] as? JsonObject
        val data = req["data"] as? JsonObject
        val render = req["render"]?.jsonPrimitive?.booleanOrNull ?: false
        val reply = if (render) renderFetch(id, url) else doFetch(id, url, method, headers, data)
        webSocket.send(reply.toString())
    }

    /** Render a page in the offscreen WebView (run JS, clear Cloudflare) and return
     *  its final DOM. For JS-only / hard sites the raw fetch can't handle. */
    private suspend fun renderFetch(id: Int, url: String): JsonObject {
        val ctx = appContext ?: return buildJsonObject {
            put("id", id); put("ok", false); put("error", "render: no context")
        }
        return try {
            val html = renderMutex.withLock { withContext(Dispatchers.Main) { renderOnMain(ctx, url) } }
            buildJsonObject {
                put("id", id); put("ok", true); put("status", 200); put("url", url)
                put("headers", buildJsonObject { put("content-type", "text/html; charset=utf-8") })
                put("body_b64", Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("id", id); put("ok", false)
                put("error", "render: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

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

    private fun doFetch(id: Int, url: String, method: String,
                        headers: JsonObject?, data: JsonObject?): JsonObject {
        return try {
            val rb = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            headers?.forEach { (k, v) -> v.jsonPrimitive.contentOrNull?.let { rb.header(k, it) } }
            if (method.equals("POST", true)) {
                val form = FormBody.Builder()
                data?.forEach { (k, v) -> v.jsonPrimitive.contentOrNull?.let { form.add(k, it) } }
                rb.post(form.build())
            } else {
                rb.get()
            }
            fetchClient.newCall(rb.build()).execute().use { resp ->
                val body = resp.body?.bytes() ?: ByteArray(0)
                buildJsonObject {
                    put("id", id)
                    put("ok", true)
                    put("status", resp.code)
                    put("url", resp.request.url.toString())
                    put("headers", buildJsonObject {
                        for (i in 0 until resp.headers.size) put(resp.headers.name(i), resp.headers.value(i))
                    })
                    put("body_b64", Base64.encodeToString(body, Base64.NO_WRAP))
                }
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("id", id)
                put("ok", false)
                put("error", e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
