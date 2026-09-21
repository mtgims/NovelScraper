package com.novelscraper.app.net

import com.novelscraper.app.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64

/** Renders a page in a real browser engine (runs its JS, clears a Cloudflare
 *  check) and returns the final DOM as HTML. */
fun interface PageRenderer {
    suspend fun render(url: String): String
}

/**
 * Scrape relay: holds a WebSocket to the backend's /api/relay and performs the
 * HTTP fetches the server asks for, from THIS device's IP, so Cloudflare
 * datacenter-ASN blocks (which 403 the server) don't apply. The server still does
 * all enumeration/parsing/pacing; we only do the raw GET/POST and return the
 * status + headers + body.
 *
 * Requests marked `render` need a browser engine ([renderer]); a platform without
 * one answers them with an error and the server falls back to a plain fetch.
 * Each platform's `ScrapeRelay` owns one of these.
 */
class RelayClient(
    private val userAgent: String,
    private val renderer: PageRenderer?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var want = false

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

    private suspend fun renderFetch(id: Int, url: String): JsonObject {
        val r = renderer ?: return buildJsonObject {
            put("id", id); put("ok", false); put("error", "render: not supported on this device")
        }
        return try {
            val html = r.render(url)
            buildJsonObject {
                put("id", id); put("ok", true); put("status", 200); put("url", url)
                put("headers", buildJsonObject { put("content-type", "text/html; charset=utf-8") })
                put("body_b64", b64(html.toByteArray(Charsets.UTF_8)))
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("id", id); put("ok", false)
                put("error", "render: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun doFetch(id: Int, url: String, method: String,
                        headers: JsonObject?, data: JsonObject?): JsonObject {
        return try {
            val rb = Request.Builder().url(url)
                .header("User-Agent", userAgent)
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
                    put("body_b64", b64(body))
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

    // Same as android.util.Base64.NO_WRAP: standard alphabet, padded, no line breaks.
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val TAG = "ScrapeRelay"
    }
}
