package com.novelscraper.app.extensions

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs real LNReader plugins against their real sites: popular list, one novel,
 * its first chapter (four requests per plugin, spaced out). Only with
 * -PlivePlugins=<id>[,<id>...]; the normal test run never touches the network.
 */
class LivePluginTest {
    private val ids = System.getProperty("live.plugins").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private val http = OkHttpClient.Builder().cookieJar(BrowserCookieJar()).build()
    private val env = PluginEnvironment(
        http = http,
        userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        storage = { mutableMapOf() },
    )

    @Test fun plugins() = runBlocking {
        if (ids.isEmpty()) return@runBlocking
        // Plugins from a local checkout of an extensions repository (-PliveRepo=<dir>),
        // else from LNReader's published index.
        val repoDir = System.getProperty("live.repo").orEmpty().takeIf { it.isNotBlank() }?.let { File(it) }
        val indexText = if (repoDir != null) File(repoDir, "index.json").readText()
            else http.newCall(Request.Builder().url(INDEX).build()).execute().use { it.body!!.string() }
        val entries = Json.parseToJsonElement(indexText).jsonArray.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        val failures = mutableListOf<String>()
        for (id in ids) {
            val entry = entries[id]?.jsonObject ?: run { failures += "$id: not in the index"; continue }
            val url = entry["url"]!!.jsonPrimitive.content
            val code = if (repoDir != null) File(repoDir, url.substringAfter("/master/")).readText()
                else http.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
            try {
                val t0 = System.nanoTime()
                PluginRuntime.load(id, code, env).use { p ->
                    val loadMs = (System.nanoTime() - t0) / 1_000_000
                    println("[$id] loaded ${p.info.name} ${p.info.version} in ${loadMs}ms")
                    val popular = p.popular(1)
                    println("[$id] popular: ${popular.size} novels, first: ${popular.firstOrNull()}")
                    assertTrue(popular.isNotEmpty(), "$id: empty popular list")
                    // Latest is its own page too, and the app offers it beside
                    // popular, so a source with a broken one is half a source.
                    delay(1500)
                    val latest = p.popular(1, latest = true)
                    println("[$id] latest: ${latest.size} novels, first: ${latest.firstOrNull()?.name}")
                    assertTrue(latest.isNotEmpty(), "$id: empty latest list")
                    // Searching is a different page on most sites, and a different
                    // way of being refused, so it is worth its own look.
                    System.getProperty("live.search").orEmpty().takeIf { it.isNotBlank() }?.let { term ->
                        delay(1500)
                        val found = p.search(term, 1)
                        println("[$id] search '$term': ${found.size} novels, first: ${found.firstOrNull()?.name}")
                        assertTrue(found.isNotEmpty(), "$id: search found nothing")
                        // A second page proves the plugin pages its search at all,
                        // which is easy to get wrong and hard to notice.
                        delay(1500)
                        val second = p.search(term, 2)
                        println("[$id] search page 2: ${second.size} novels, first: ${second.firstOrNull()?.name}")
                    }
                    // Try up to three novels: one may have locked or removed chapters.
                    var lastError: Throwable? = null
                    var ok = false
                    for (item in popular.take(3)) {
                        try {
                            delay(1500)
                            val novel = p.novel(item.path)
                            println("[$id] novel: '${novel.name}' by ${novel.author}, status ${novel.status}, " +
                                "${novel.chapters.size} chapters, pages ${novel.totalPages}, cover ${novel.cover}")
                            val first = novel.chapters.firstOrNull() ?: run {
                                if (novel.totalPages != null && p.info.hasParsePage) { delay(1500); p.page(novel.path, 1).chapters.firstOrNull() } else null
                            } ?: error("no chapters")
                            delay(1500)
                            val c0 = System.nanoTime()
                            val html = p.chapter(first.path)
                            val plain = com.novelscraper.app.data.Sentences.plain(html)
                            println("[$id] chapter '${first.name}': ${html.length} chars of HTML, ${plain.length} of text " +
                                "(${(System.nanoTime() - c0) / 1_000_000}ms): ${plain.take(160).replace('\n', ' ')}")
                            check(plain.length > 200) { "chapter text too short" }
                            ok = true
                            break
                        } catch (t: Throwable) {
                            println("[$id] '${item.name}': $t")
                            lastError = t
                        }
                    }
                    if (!ok) throw lastError ?: AssertionError("$id: nothing worked")
                }
            } catch (t: Throwable) {
                println("[$id] FAILED: $t")
                failures += "$id: $t"
            }
            delay(1500)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private companion object {
        const val INDEX = "https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"
    }
}
