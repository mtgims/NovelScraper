package com.novelscraper.app.extensions

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import kotlinx.coroutines.launch
import kotlin.test.Test

/**
 * How long the app itself spends on a source's calls, with the network taken
 * out: each plugin is run against its recorded pages
 * (`~/novelscraper-extensions/recordings`), served from memory. Only with
 * -PbenchPlugins=<extensions checkout>; the normal run skips it.
 */
class PluginBenchmark {
    private val repo = System.getProperty("bench.repo").orEmpty().takeIf { it.isNotBlank() }?.let { File(it) }

    private class Recorded(val status: Int, val headers: Map<String, String>, val body: ByteArray, val finalUrl: String)

    private fun recordings(dir: File): Map<String, Recorded> {
        val out = LinkedHashMap<String, Recorded>()
        dir.listFiles { f -> f.name.endsWith(".json") && f.name != "expected.json" }!!.sortedBy { it.name }.forEach { f ->
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            if (o["error"] != null) return@forEach
            val body = o["base64"]?.jsonPrimitive?.content?.let { java.util.Base64.getDecoder().decode(it) }
                ?: (o["text"]?.jsonPrimitive?.content ?: "").toByteArray()
            val headers = (o["headers"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
                .filterKeys { it !in setOf("content-encoding", "content-length", "transfer-encoding") }
            val r = Recorded(o["status"]!!.jsonPrimitive.content.toInt(), headers, body,
                o["finalUrl"]?.jsonPrimitive?.contentOrNull ?: o["url"]!!.jsonPrimitive.content)
            val method = o["method"]?.jsonPrimitive?.content ?: "GET"
            out.putIfAbsent("$method ${o["url"]!!.jsonPrimitive.content}", r)
            out.putIfAbsent("* ${o["url"]!!.jsonPrimitive.content}", r)
            // Same path, any query: what test-plugins.mjs falls back to as well.
            out.putIfAbsent("~ ${o["url"]!!.jsonPrimitive.content.substringBefore('?')}", r)
        }
        return out
    }

    /** Requests the recordings had no answer for: a call that met one is not measured. */
    private val misses = java.util.concurrent.atomic.AtomicInteger()

    private fun client(rec: Map<String, Recorded>) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        val url = req.url.toString()
        val r = rec["${req.method} $url"] ?: rec["* $url"] ?: rec["~ ${url.substringBefore('?')}"]
        val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
        if (r == null) { misses.incrementAndGet(); b.code(404).message("Not recorded").body(ByteArray(0).toResponseBody()).build() }
        else {
            r.headers.forEach { (k, v) -> b.header(k, v) }
            b.code(r.status).message("").body(r.body.toResponseBody(r.headers["content-type"]?.toMediaTypeOrNull())).build()
        }
    }).build()

    /** Times [block]; [size] says how much came back, so an empty answer can't pass for a fast one. */
    /** With -PbenchDump=<dir>: every result written out, to diff one build's plugins against another's. */
    private val dump = System.getProperty("bench.dump").orEmpty().takeIf { it.isNotBlank() }?.let { File(it) }
    private var dumpTo: File? = null
    private var dumpSeq = 0

    private inline fun <T> timed(label: String, sink: MutableList<String>, size: (T) -> Int = { -1 }, block: () -> T): T? {
        val missed = misses.get()
        val t0 = System.nanoTime()
        return try {
            block().also {
                val ms = (System.nanoTime() - t0) / 1_000_000
                val n = size(it).takeIf { n -> n >= 0 }?.let { n -> " ($n)" }.orEmpty()
                val miss = if (misses.get() != missed) " UNRECORDED" else ""
                sink += "$label ${ms}ms$n$miss"
                dumpTo?.let { d -> File(d, "%02d-%s.txt".format(++dumpSeq, label)).writeText(it.toString()) }
            }
        } catch (t: Throwable) {
            sink += "$label FAILED ${(System.nanoTime() - t0) / 1_000_000}ms (${t.message?.take(60)})"
            null
        }
    }

    @Test fun benchmark() = runBlocking {
        val repo = repo ?: return@runBlocking
        val index = Json.parseToJsonElement(File(repo, "index.json").readText()).jsonArray
            .associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        val only = System.getProperty("bench.only").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        val rounds = if (System.getProperty("bench.live") == "true") 1 else System.getProperty("bench.rounds")?.toIntOrNull() ?: 3
        File(repo, "recordings").listFiles()!!.sortedBy { it.name }.forEach { dir ->
            val id = dir.name
            if (only.isNotEmpty() && id !in only) return@forEach
            val entry = index[id]?.jsonObject ?: return@forEach
            val code = File(repo, entry["url"]!!.jsonPrimitive.content.substringAfter("/master/")).readText()
            // Live (-PbenchLive=true): the real sites, each request timed, to set
            // the network's share beside the app's own.
            val live = System.getProperty("bench.live") == "true"
            val http = if (!live) client(recordings(dir)) else OkHttpClient.Builder().cookieJar(BrowserCookieJar())
                .eventListenerFactory { Phases(id) }
                .addNetworkInterceptor(Interceptor { chain ->
                    val t0 = System.nanoTime()
                    chain.proceed(chain.request()).also { r ->
                        // Whole body read here, so its download counts as network.
                        val bytes = r.peekBody(Long.MAX_VALUE).contentLength()
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        println("[$id]   ${r.code} ${ms}ms ${bytes}B ${r.protocol} ${chain.request().url.toString().take(90)}")
                    }
                }).build()
            // Spaced out live, as LivePluginTest is: sites refuse a burst.
            suspend fun pause() { if (live) kotlinx.coroutines.delay(1500) }
            val env = PluginEnvironment(http,
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                storage = { mutableMapOf() }, allowPrivateHosts = !live)
            repeat(rounds) { round ->
                dumpTo = dump?.takeIf { round == 0 }?.let { File(it, id).apply { deleteRecursively(); mkdirs() } }
                dumpSeq = 0
                val lines = mutableListOf<String>()
                val rt = timed("load", lines) { PluginRuntime.load(id, code, env) } ?: run { println("[$id] $lines"); return@forEach }
                var idleKiB = 0L
                rt.use { p ->
                    val popular = timed("popular", lines, { it.size }) { p.popular(1) }.orEmpty()
                    pause(); timed("latest", lines, { it.size }) { p.popular(1, latest = true) }
                    pause(); timed("search", lines, { it.size }) { p.search("sword", 1) }
                    for (item in popular.take(3)) {
                        pause()
                        val novel = timed("novel", lines, { it.chapters.size }) { p.novel(item.path) } ?: continue
                        val first = novel.chapters.firstOrNull() ?: continue
                        pause()
                        timed("chapter", lines, { it.length }) { p.chapter(first.path) } ?: continue
                        break
                    }
                    idleKiB = (PluginRuntime::class.java.getDeclaredField("js").apply { isAccessible = true }
                        .get(p) as com.dokar.quickjs.QuickJs).memoryUsage.memoryUsedSize / 1024
                }
                // What a loaded source holds once its calls are done (the runtime is closed by now, so measured before).
                println("[$id] round ${round + 1}: ${lines.joinToString(", ")}, idle ${idleKiB} KiB")
            }
        }
    }

    /** Live: how long finding the host, connecting and the TLS handshake took, per new connection. */
    private class Phases(val id: String) : okhttp3.EventListener() {
        private var call0 = 0L; private var dns0 = 0L; private var conn0 = 0L; private var tls0 = 0L
        private var dns = 0L; private var tls = 0L
        override fun callStart(call: okhttp3.Call) { call0 = System.nanoTime() }
        override fun dnsStart(call: okhttp3.Call, domainName: String) { dns0 = System.nanoTime() }
        override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) { dns = (System.nanoTime() - dns0) / 1_000_000 }
        override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { conn0 = System.nanoTime() }
        override fun secureConnectStart(call: okhttp3.Call) { tls0 = System.nanoTime() }
        override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) { tls = (System.nanoTime() - tls0) / 1_000_000 }
        override fun connectEnd(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: Protocol?) {
            val all = (System.nanoTime() - conn0) / 1_000_000
            println("[$id]   new connection to ${call.request().url.host}: dns ${dns}ms, tcp ${all - tls}ms, tls ${tls}ms")
        }
    }

    /**
     * Live, with -PbenchSearch=<n>[,<n>...]: one search on every recorded source,
     * n sources at a time, as the app's search-everything does; the runs
     * alternate and are spaced out, so no site is asked twice in a row.
     */
    @Test fun searchEverything() = runBlocking {
        val repo = repo ?: return@runBlocking
        val widths = System.getProperty("bench.search").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        if (widths.isEmpty()) return@runBlocking
        val index = Json.parseToJsonElement(File(repo, "index.json").readText()).jsonArray
            .associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        val codes = File(repo, "recordings").listFiles()!!.sortedBy { it.name }.mapNotNull { dir ->
            val entry = index[dir.name]?.jsonObject ?: return@mapNotNull null
            dir.name to File(repo, entry["url"]!!.jsonPrimitive.content.substringAfter("/master/")).readText()
        }
        val env = PluginEnvironment(OkHttpClient.Builder().cookieJar(BrowserCookieJar()).build(),
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            storage = { mutableMapOf() })
        for ((n, width) in widths.withIndex()) {
            if (n > 0) kotlinx.coroutines.delay(20_000)
            val slots = kotlinx.coroutines.sync.Semaphore(width)
            val done = java.util.concurrent.ConcurrentHashMap<String, String>()
            val t0 = System.nanoTime()
            kotlinx.coroutines.coroutineScope {
                for ((id, code) in codes) launch(kotlinx.coroutines.Dispatchers.Default) {
                    slots.acquire()
                    try {
                        val s0 = System.nanoTime()
                        val r = runCatching { PluginRuntime.load(id, code, env).use { it.search("sword", 1, unattended = true).size } }
                        done[id] = "${(System.nanoTime() - s0) / 1_000_000}ms " + (r.getOrNull()?.toString() ?: "failed")
                    } finally { slots.release() }
                }
            }
            println("[search] $width at a time: all ${codes.size} in ${(System.nanoTime() - t0) / 1_000_000}ms; " +
                done.toSortedMap().entries.joinToString(", ") { "${it.key.removePrefix("novelscraper.")} ${it.value}" })
        }
    }
}
