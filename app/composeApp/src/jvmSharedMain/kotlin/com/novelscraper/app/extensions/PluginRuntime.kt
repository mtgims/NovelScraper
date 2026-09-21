package com.novelscraper.app.extensions

import com.dokar.quickjs.ExperimentalQuickJsApi
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.alias.def
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.novelscraper.app.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * One LNReader-format plugin, running in its own QuickJS context with the host
 * prelude (plugin-host/host.js: the modules and web APIs plugins expect). All
 * calls are serialized and time-limited; results come back as JSON and are
 * decoded into [NovelItem] / [SourceNovel] / ....
 *
 * [PluginEnvironment] supplies what the JavaScript side can't do itself: HTTP
 * (from this device, with a browser-like cookie jar), per-plugin storage and the
 * device's User-Agent.
 */
class PluginRuntime private constructor(
    private val js: QuickJs,
    private val env: PluginEnvironment,
) : Closeable {

    lateinit var info: PluginInfo
        private set

    private val mutex = Mutex()

    /** A page in the current call answered with a Cloudflare-style browser check. */
    @Volatile private var challengedUrl: String? = null

    suspend fun popular(page: Int, latest: Boolean = false): List<NovelItem> =
        decode(call("__popular(${q(info.id)}, $page, $latest)"))

    suspend fun search(term: String, page: Int = 1): List<NovelItem> =
        decode(call("__search(${q(info.id)}, ${q(term)}, $page)"))

    suspend fun novel(path: String): SourceNovel =
        decode(call("__novel(${q(info.id)}, ${q(path)})"))

    /** More chapters of a paged novel (plugins with [PluginInfo.hasParsePage]). */
    suspend fun page(path: String, page: Int): SourcePage =
        decode(call("__page(${q(info.id)}, ${q(path)}, $page)"))

    /** The chapter's content as HTML. */
    suspend fun chapter(path: String): String = call("__chapter(${q(info.id)}, ${q(path)})")

    /** The web address of a novel or chapter path, when the plugin knows how. */
    suspend fun resolveUrl(path: String, isNovel: Boolean): String? =
        call("__resolveUrl(${q(info.id)}, ${q(path)}, $isNovel)").ifBlank { null }

    override fun close() = js.close()

    /** Run a host.js entry point and return its (string) result, or throw the
     *  plugin's error as [PluginException]. */
    private suspend fun call(expr: String): String = mutex.withLock {
        withTimeout(CALL_TIMEOUT_MS) {
            challengedUrl = null
            js.evaluate<Any?>("__call(() => $expr)")
            val r = json.parseToJsonElement(js.evaluate<String>("__take()")).jsonObject
            val value = r["v"]?.jsonPrimitive?.contentOrNull ?: ""
            val ok = r["ok"]?.jsonPrimitive?.content == "true"
            // A site behind a browser check makes plugins fail or come back empty;
            // say so rather than showing "nothing found".
            challengedUrl?.let { url ->
                if (!ok || value.isBlank() || value == "[]" || value == "null") throw SiteChallengeException(info.id, url)
            }
            if (!ok) throw PluginException(info.id, value)
            value
        }
    }

    private inline fun <reified T> decode(s: String): T = json.decodeFromString(s)

    companion object {
        private const val TAG = "Plugins"
        private const val CALL_TIMEOUT_MS = 90_000L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        private val prelude: String by lazy {
            checkNotNull(PluginRuntime::class.java.getResourceAsStream("/plugin-host/host.js")) {
                "plugin-host/host.js missing from the app"
            }.use { it.readBytes().decodeToString() }
        }

        /** Start a context, run the host prelude and the plugin's code. Throws
         *  (with the plugin's error) if the code doesn't load. */
        suspend fun load(pluginId: String, code: String, env: PluginEnvironment): PluginRuntime {
            val js = QuickJs.create(Dispatchers.Default)
            try {
                js.memoryLimit = 256L * 1024 * 1024
                val rt = PluginRuntime(js, env)
                rt.bindNative(pluginId)
                js.evaluate<Any?>(prelude, "host.js", false)
                val infoJson = js.evaluate<String>("__loadPlugin(${q(pluginId)}, ${q(code)})", "$pluginId.js", false)
                rt.info = json.decodeFromString(infoJson)
                return rt
            } catch (t: Throwable) {
                js.close()
                throw t
            }
        }

        /** A JavaScript string literal (JSON strings are valid JS). */
        private fun q(s: String): String = JsonPrimitive(s).toString()
    }

    // --- globalThis.__native (see host.js) -------------------------------------

    private val responseBodies = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > 16
    }
    private val responseSeq = AtomicLong()

    @OptIn(ExperimentalQuickJsApi::class)
    private fun bindNative(pluginId: String) {
        js.def("__native") {
            function("log", FunctionBinding { a ->
                val level = a.getOrNull(0) as? String ?: "info"
                val msg = "[$pluginId] ${a.getOrNull(1)}"
                when (level) { "warn", "error" -> Log.w(TAG, msg); "debug" -> Log.d(TAG, msg); else -> Log.i(TAG, msg) }
                null
            })
            function("userAgent", FunctionBinding { env.userAgent })
            asyncFunction("sleep", AsyncFunctionBinding { a ->
                delay((a.getOrNull(0) as? String)?.toLongOrNull() ?: 0L); null
            })
            asyncFunction("fetch", AsyncFunctionBinding { a -> fetch(a[0] as String) })
            function("body", FunctionBinding { a ->
                val id = (a.getOrNull(0) as? String)?.toLongOrNull()
                val bytes = synchronized(responseBodies) { id?.let(responseBodies::get) }
                Base64.getEncoder().encodeToString(bytes ?: ByteArray(0))
            })
            function("decode", FunctionBinding { a ->
                val bytes = Base64.getDecoder().decode(a[0] as String)
                String(bytes, charset(a.getOrNull(1) as? String))
            })
            function("urlencode", FunctionBinding { a ->
                val o = json.parseToJsonElement(a[0] as String).jsonObject
                val s = o["s"]!!.jsonPrimitive.content
                val cs = charset(o["charset"]?.jsonPrimitive?.contentOrNull)
                if (o["op"]?.jsonPrimitive?.content == "decode") URLDecoder.decode(s, cs)
                else URLEncoder.encode(s, cs).replace("+", "%20")
            })
            function("storageGet", FunctionBinding { a -> env.storage(a[0] as String)[a[1] as String] ?: "" })
            function("storageSet", FunctionBinding { a ->
                env.storage(a[0] as String)[a[1] as String] = a[2] as String; null
            })
            function("storageDelete", FunctionBinding { a -> env.storage(a[0] as String).remove(a[1] as String); null })
            function("storageKeys", FunctionBinding { a ->
                JsonArray(env.storage(a[0] as String).keys.map { JsonPrimitive(it) }).toString()
            })
        }
    }

    private fun charset(label: String?): Charset =
        runCatching { Charset.forName(label?.trim().takeUnless { it.isNullOrEmpty() } ?: "UTF-8") }
            .getOrDefault(Charsets.UTF_8)

    /** host.js rawFetch -> one HTTP request from this device. */
    private suspend fun fetch(requestJson: String): String = withContext(Dispatchers.IO) {
        try {
            val r = json.parseToJsonElement(requestJson).jsonObject
            val url = r["url"]!!.jsonPrimitive.content
            val method = r["method"]?.jsonPrimitive?.content ?: "GET"
            val headers = (r["headers"] as? JsonObject).orEmpty()
                .mapValues { it.value.jsonPrimitive.content }
            val builder = Request.Builder().url(url)
            for ((k, v) in headers) {
                // OkHttp handles compression itself, but only when it set this header.
                if (k.equals("accept-encoding", true)) continue
                builder.header(k, v)
            }
            val contentType = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
            builder.method(method, requestBody(method, r["body"] as? JsonObject, contentType))
            env.http.newCall(builder.build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (isChallenge(resp.code, resp.headers, bytes)) challengedUrl = url
                val id = responseSeq.incrementAndGet()
                synchronized(responseBodies) { responseBodies[id] = bytes }
                // Like fetch's Response.text(): UTF-8 unless the caller (fetchText) asks.
                val encoding = r["encoding"]?.jsonPrimitive?.contentOrNull
                buildJsonObject {
                    put("status", resp.code)
                    put("statusText", resp.message)
                    put("url", resp.request.url.toString())
                    put("redirected", resp.priorResponse != null)
                    put("headers", buildJsonObject {
                        for (name in resp.headers.names()) put(name.lowercase(), resp.headers.values(name).joinToString(", "))
                    })
                    put("text", String(bytes, charset(encoding)))
                    put("bodyId", id.toString())
                }.toString()
            }
        } catch (e: Exception) {
            buildJsonObject { put("error", e.message ?: e.javaClass.simpleName) }.toString()
        }
    }

    /** Cloudflare's interstitial ("Just a moment..."): 403/503 with its marker
     *  header or page. */
    private fun isChallenge(code: Int, headers: okhttp3.Headers, body: ByteArray): Boolean {
        if (code != 403 && code != 503) return false
        if (headers["cf-mitigated"].equals("challenge", ignoreCase = true)) return true
        val head = String(body, 0, minOf(body.size, 4096), Charsets.UTF_8)
        return headers["server"]?.contains("cloudflare", ignoreCase = true) == true &&
            (head.contains("Just a moment", ignoreCase = true) || head.contains("cf-chl"))
    }

    private fun requestBody(method: String, body: JsonObject?, contentType: String?): okhttp3.RequestBody? {
        if (method == "GET" || method == "HEAD") return null
        val kind = body?.get("kind")?.jsonPrimitive?.content
        val value = body?.get("value")
        return when (kind) {
            "form" -> (value!!.jsonPrimitive.content)
                .toRequestBody((contentType ?: "application/x-www-form-urlencoded;charset=UTF-8").toMediaTypeOrNull())
            "multipart" -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                for (entry in value!!.jsonArray) {
                    val (k, v) = entry.jsonArray.map { it.jsonPrimitive.content }
                    addFormDataPart(k, v)
                }
            }.build()
            "bytes" -> Base64.getDecoder().decode(value!!.jsonPrimitive.content)
                .toRequestBody((contentType ?: "application/octet-stream").toMediaTypeOrNull())
            "text" -> value!!.jsonPrimitive.content
                .toRequestBody((contentType ?: "text/plain;charset=UTF-8").toMediaTypeOrNull())
            else -> ByteArray(0).toRequestBody(null)  // POST without a body
        }
    }
}

/** The site answered with a browser check (Cloudflare) that needs a real browser
 *  to pass; the plugin couldn't get through. */
class SiteChallengeException(val pluginId: String, val url: String) :
    Exception("The site asks for a browser check: $url")

/** A plugin threw (its message, often with a JavaScript stack). */
class PluginException(val pluginId: String, message: String) : Exception(message.lineSequence().first()) {
    val details: String = message
}

/** What a plugin can reach outside its JavaScript context. */
class PluginEnvironment(
    val http: OkHttpClient,
    val userAgent: String,
    /** A plugin's key/value storage (values are opaque JSON strings). */
    val storage: (pluginId: String) -> MutableMap<String, String>,
)
