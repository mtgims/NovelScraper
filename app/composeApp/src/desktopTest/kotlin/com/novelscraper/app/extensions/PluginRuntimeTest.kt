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
                // Answers long after the host has given up on the call that
                // asked, which is the situation the call slot has to survive.
                "/slow" -> {
                    Thread.sleep(2_500)
                    Triple(200, "text/plain", "SLOW-ANSWER".toByteArray())
                }
                "/set-cookie" -> {
                    ex.responseHeaders.add("Set-Cookie", "sid=secret; Path=/")
                    Triple(200, "text/plain", "set".toByteArray())
                }
                // Reports back whatever the client chose to send, which is what
                // the cookie-isolation test actually needs to see.
                "/echo-cookie" ->
                    Triple(200, "text/plain", (ex.requestHeaders.getFirst("Cookie") ?: "(none)").toByteArray())
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
    // The fixtures are served from 127.0.0.1, which the shipped policy refuses;
    // aPluginCannotReachTheLocalNetwork below covers that policy itself.
    private val env = PluginEnvironment(
        OkHttpClient(), "TestAgent/1.0",
        { id -> store.getOrPut(id) { mutableMapOf() } },
        allowPrivateHosts = true,
    )

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


    /**
     * Storage is per plugin, and the plugin does not get to say which one.
     *
     * `globalThis.__native` is reachable from plugin code: host.js captures it
     * but never removes it. The bindings used to take the plugin id from their
     * first argument, so naming another source returned whatever it had stored,
     * which is where a source keeps its session and any credentials.
     */
    @Test fun aPluginCannotReachAnotherPluginsStorage() = runBlocking {
        store["victim"] = mutableMapOf("token" to "secret-session")
        val attacker = """
            const N = globalThis.__native;
            const stolen = N.storageGet('victim', 'token');
            N.storageSet('victim', 'planted', 'yes');
            class Attacker {
              id = 'attacker'; name = 'Attacker'; site = '$base/'; version = '1.0.0';
              async popularNovels(page) { return [{ name: 'got[' + stolen + ']', path: 'p' }]; }
              async searchNovels(term, page) { return []; }
              async parseNovel(path) { return { name: 'n', path, chapters: [] }; }
              async parseChapter(path) { return ''; }
            }
            exports.default = new Attacker();
        """.trimIndent()

        PluginRuntime.load("attacker", attacker, env).use { p ->
            assertEquals("got[]", p.popular(1).single().name,
                "reading another plugin's key returns nothing, not its value")
        }
        assertEquals("secret-session", store["victim"]!!["token"], "the victim's own value is intact")
        assertTrue("planted" !in store["victim"]!!, "a write aimed at the victim did not land there")
        assertEquals("yes", store["attacker"]?.get("planted"), "it landed in the caller's own storage")
    }

    /**
     * A source is third-party code on someone's device, and from there it can
     * see a network the author cannot: a router's admin page, a NAS, anything
     * else listening at home. The refusal is at the DNS layer, so a redirect
     * onto a private address is refused too.
     */
    @Test fun aPluginCannotReachTheLocalNetwork() = runBlocking<Unit> {
        val walled = PluginEnvironment(
            OkHttpClient(), "TestAgent/1.0", { mutableMapOf() },
        )  // allowPrivateHosts defaults to false, as it ships
        val prober = """
            class Prober {
              id = 'prober'; name = 'Prober'; site = '$base/'; version = '1.0.0';
              async popularNovels(page) {
                const { fetchApi } = require('@libs/fetch');
                const r = await fetchApi('$base/list');
                return [{ name: await r.text(), path: 'p' }];
              }
              async searchNovels(term, page) { return []; }
              async parseNovel(path) { return { name: 'n', path, chapters: [] }; }
              async parseChapter(path) { return ''; }
            }
            exports.default = new Prober();
        """.trimIndent()

        PluginRuntime.load("prober", prober, walled).use { p ->
            assertFailsWith<Exception>("reaching 127.0.0.1 must not succeed") { p.popular(1) }
        }
    }

    /**
     * One plugin must not ride another's session.
     *
     * The jar scopes cookies by site, as a browser does, but a browser has one
     * principal and this has two. A source asking for a site another source had
     * logged into used to get that session attached automatically, and could
     * read the reply; it never touched the cookie, so keeping plugins out of
     * each other's storage does nothing about it.
     *
     * What a browser check earns stays shared: clearance says "not a bot"
     * rather than "this is who I am", and isolating it would mean a captcha per
     * plugin for the same site.
     */
    @Test fun aPluginCannotUseAnotherPluginsSession() = runBlocking<Unit> {
        val jar = BrowserCookieJar()
        val shared = PluginEnvironment(
            OkHttpClient(), "TestAgent/1.0", { mutableMapOf() },
            allowPrivateHosts = true, cookies = jar,
        )
        fun code(id: String, path: String) = """
            class P {
              id = '$id'; name = '$id'; site = '$base/'; version = '1.0.0';
              async popularNovels(page) {
                const { fetchApi } = require('@libs/fetch');
                const r = await fetchApi('$base$path');
                return [{ name: await r.text(), path: 'p' }];
              }
              async searchNovels(term, page) { return []; }
              async parseNovel(path) { return { name: 'n', path, chapters: [] }; }
              async parseChapter(path) { return ''; }
            }
            exports.default = new P();
        """.trimIndent()

        // alpha signs in; the site sets a session for it.
        PluginRuntime.load("alpha", code("alpha", "/set-cookie"), shared).use { it.popular(1) }

        PluginRuntime.load("beta", code("beta", "/echo-cookie"), shared).use { p ->
            assertEquals("(none)", p.popular(1).single().name,
                "beta asked the same site and must not carry alpha's session")
        }
        PluginRuntime.load("alpha", code("alpha", "/echo-cookie"), shared).use { p ->
            assertTrue("sid=secret" in p.popular(1).single().name, "alpha still has its own")
        }

        // Clearance earned by a real browser is shared, so one captcha serves all.
        jar.acceptFromBrowser(
            "$base/".toHttpUrl(),
            listOf(BrowserCookieJar.BrowserCookie(
                "cf_clearance", "pass", null, "/",
                System.currentTimeMillis() + 86_400_000L, false, false)),
        )
        PluginRuntime.load("beta", code("beta", "/echo-cookie"), shared).use { p ->
            val sent = p.popular(1).single().name
            assertTrue("cf_clearance=pass" in sent, "beta gets the shared clearance: $sent")
            assertTrue("sid=secret" !in sent, "but still not alpha's session: $sent")
        }
    }


    /**
     * A call the host gave up on must not answer the next one.
     *
     * The outcome slot is a single global. A timed-out call leaves its promise
     * pending, and when the fetch it was waiting on finally returns, that
     * continuation used to write into the slot, which by then belonged to a
     * different call: one request quietly received another's answer.
     */
    @Test fun anAbandonedCallCannotAnswerTheNextOne() = runBlocking<Unit> {
        val previous = PluginRuntime.callTimeoutMs
        PluginRuntime.callTimeoutMs = 700
        try {
            val code = """
                class P {
                  id = 'slowpoke'; name = 'Slowpoke'; site = '$base/'; version = '1.0.0';
                  async popularNovels(page) {
                    const { fetchApi } = require('@libs/fetch');
                    const r = await fetchApi(page === 1 ? '$base/slow' : '$base/list');
                    return [{ name: (await r.text()).slice(0, 24), path: 'p' }];
                  }
                  async searchNovels(term, page) { return []; }
                  async parseNovel(path) { return { name: 'n', path, chapters: [] }; }
                  async parseChapter(path) { return ''; }
                }
                exports.default = new P();
            """.trimIndent()

            PluginRuntime.load("slowpoke", code, env).use { p ->
                assertFailsWith<Exception>("the slow page must not beat the timeout") { p.popular(1) }
                // Long enough for the abandoned fetch to come back underneath.
                kotlinx.coroutines.delay(3_000)
                val answer = p.popular(2).single().name
                assertTrue("SLOW-ANSWER" !in answer, "got the abandoned call's answer: $answer")
                assertTrue(answer.startsWith("<ul>"), "got its own answer: $answer")
            }
        } finally {
            PluginRuntime.callTimeoutMs = previous
        }
    }
}
