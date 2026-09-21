package com.novelscraper.app.extensions

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The plugin host, offline: a fake LNReader-format plugin against a local server. */
class PluginRuntimeTest {
    private val requests = mutableListOf<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            synchronized(requests) {
                requests += "${ex.requestMethod} ${ex.requestURI} ua=${ex.requestHeaders.getFirst("User-Agent")} " +
                    "ct=${ex.requestHeaders.getFirst("Content-Type")} body=$body"
            }
            val path = ex.requestURI.path
            val (code, type, bytes) = when (path) {
                "/list" -> Triple(200, "text/html", """<ul><li><a href="/n/1" data-c="c1.jpg">One</a></li><li><a href="/n/2">Two &amp; more</a></li></ul>""".toByteArray())
                "/gbk" -> Triple(200, "text/html; charset=gbk", "<p>你好，世界</p>".toByteArray(charset("GBK")))
                "/gzip" -> {
                    // Served compressed whatever the client asked for, as some sites do.
                    ex.responseHeaders.add("Content-Encoding", "gzip")
                    Triple(200, "text/plain", java.io.ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write("compressed ok".toByteArray()) } }.toByteArray())
                }
                "/cf" -> {
                    ex.responseHeaders.add("cf-mitigated", "challenge")
                    Triple(403, "text/html", "<title>Just a moment...</title>".toByteArray())
                }
                else -> Triple(200, "text/plain", "echo".toByteArray())
            }
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val store = HashMap<String, MutableMap<String, String>>()
    private val env = PluginEnvironment(OkHttpClient(), "TestAgent/1.0") { id -> store.getOrPut(id) { mutableMapOf() } }

    @AfterTest fun stop() = server.stop(0)

    private val plugin = """
        const { fetchApi, fetchText } = require('@libs/fetch');
        const { load } = require('cheerio');
        const { NovelStatus } = require('@libs/novelStatus');
        const { storage } = require('@libs/storage');
        const dayjs = require('dayjs');
        const site = '$base/';
        class Fake {
          id = 'fake'; name = 'Fake'; site = site; version = '1.2.3';
          async popularNovels(page, { showLatestNovels }) {
            const ${'$'} = load(await (await fetchApi(site + 'list?page=' + page)).text());
            return ${'$'}('a').map((_, a) => ({ name: ${'$'}(a).text(), path: ${'$'}(a).attr('href').slice(1),
              cover: ${'$'}(a).attr('data-c') ? new URL(${'$'}(a).attr('data-c'), site).href : undefined })).get();
          }
          async searchNovels(term, page) {
            const form = new URLSearchParams({ q: term, page: String(page) });
            await fetchApi(site + 'search', { method: 'POST', body: form });
            const fd = new FormData(); fd.append('x', '1');
            await fetchApi(site + 'multi', { method: 'POST', body: fd });
            return [{ name: term, path: 'q' }];
          }
          async parseNovel(path) {
            storage.set('visits', (storage.get('visits') || 0) + 1);
            storage.set('gone', 'x', Date.now() - 1000);
            const gbk = await fetchText(site + 'gbk', {}, 'gbk');
            const gz = await (await fetchApi(site + 'gzip')).text();
            await new Promise(r => setTimeout(r, 20));
            return { name: 'N' + path, path, status: NovelStatus.Completed,
              summary: [gbk, gz, String(storage.get('visits')), String(storage.get('gone')), btoa('hi'), dayjs('2026-01-02').format('YYYY')].join('|'),
              chapters: [{ name: 'C1', path: 'c/1', chapterNumber: 1 }] };
          }
          async parseChapter(path) {
            if (path === 'boom') throw new Error('broken on purpose');
            if (path === 'cf') { await fetchApi(site + 'cf'); return ''; }
            return '<p>' + path + '</p>';
          }
        }
        exports.default = new Fake();
    """.trimIndent()

    @Test fun runsAPluginLikeLnReaderDoes() = runBlocking {
        PluginRuntime.load("fake", plugin, env).use { p ->
            assertEquals("1.2.3", p.info.version)
            val list = p.popular(1)
            assertEquals(listOf("One", "Two & more"), list.map { it.name })
            assertEquals("$base/c1.jpg", list[0].cover)

            val novel = p.novel("n/1")
            assertEquals("Completed", novel.status)
            assertEquals("<p>你好，世界</p>|compressed ok|1|undefined|aGk=|2026", novel.summary)
            assertTrue(store["fake"]!!["visits"]!!.startsWith("""{"created":"""), "storage holds LNReader's item format")
            assertEquals(1, novel.chapters.size)

            assertEquals("<p>c/1</p>", p.chapter("c/1"))
            p.search("dragon", 2)
            synchronized(requests) {
                assertTrue(requests.any { it.startsWith("GET /list?page=1 ua=TestAgent/1.0") }, requests.toString())
                assertTrue(requests.any { it.startsWith("POST /search") && "application/x-www-form-urlencoded" in it && "q=dragon&page=2" in it }, requests.toString())
                assertTrue(requests.any { it.startsWith("POST /multi") && "multipart/form-data" in it && "name=\"x\"" in it }, requests.toString())
            }
        }
    }

    @Test fun errorsAndChallengesSurface() = runBlocking {
        PluginRuntime.load("fake", plugin, env).use { p ->
            val e = assertFailsWith<PluginException> { p.chapter("boom") }
            assertTrue("broken on purpose" in e.message!!)
            assertFailsWith<SiteChallengeException> { p.chapter("cf") }
            assertEquals("<p>after</p>", p.chapter("after"), "the runtime keeps working after a failure")
        }
    }

    @Test fun badCodeDoesNotLoad() {
        runBlocking {
            assertFailsWith<Exception> { PluginRuntime.load("bad", "exports.default = undefined;", env) }
            assertFailsWith<Exception> { PluginRuntime.load("bad", "this is not javascript", env) }
            assertFailsWith<Exception> { PluginRuntime.load("bad", "require('no-such-module')", env) }
        }
    }

    @Test fun versions() {
        assertTrue(compareVersions("2.10.0", "2.9.1") > 0)
        assertTrue(compareVersions("1.0", "1.0.1") < 0)
        assertEquals(0, compareVersions("1.0.0", "1.0"))
    }

    @Test fun cookieJarKeepsAndExpires() {
        val jar = BrowserCookieJar()
        val url = "https://site.example/novel/1".toHttpUrl()
        val now = System.currentTimeMillis()
        jar.saveFromResponse(url, listOf(
            Cookie.Builder().domain("site.example").path("/").name("cf_clearance").value("ok").expiresAt(now + 60_000).build(),
            Cookie.Builder().domain("site.example").path("/").name("old").value("x").expiresAt(now - 1).build(),
        ))
        assertEquals(listOf("cf_clearance"), jar.loadForRequest(url).map { it.name })
        assertTrue(jar.loadForRequest("https://other.example/".toHttpUrl()).isEmpty())
    }

}
