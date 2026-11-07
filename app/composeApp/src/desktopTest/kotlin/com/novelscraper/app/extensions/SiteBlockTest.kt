package com.novelscraper.app.extensions

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A site whose firewall turns this connection away is asked through the
 *  browser (it often lets a browser in: Scribble Hub over a VPN), and says so
 *  only when nothing got through, rather than coming back as "nothing found". */
class SiteBlockTest {

    private val page = javaClass.getResource("/cloudflare-blocked.html")!!.readBytes()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/blocked") { ex ->
            ex.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            ex.responseHeaders.add("Server", "cloudflare")
            ex.sendResponseHeaders(403, page.size.toLong())
            ex.responseBody.use { it.write(page) }
        }
        start()
    }

    private val blockedUrl = "http://127.0.0.1:${server.address.port}/blocked"

    @AfterTest fun stop() {
        server.stop(0)
        SiteChecks.plainAgain(blockedUrl)
    }

    private val env = PluginEnvironment(OkHttpClient(), "TestAgent/1.0", { mutableMapOf() }, allowPrivateHosts = true)

    /** A plugin that lists what it finds on [path], or [orElse] when refused. */
    private fun plugin(id: String, path: String, orElse: String = "[]") = """
        class P {
          id = '$id'; name = 'P'; site = 'http://127.0.0.1:${server.address.port}'; version = '1.0.0';
          async popularNovels(page) {
            const { fetchApi } = require('@libs/fetch');
            const r = await fetchApi(this.site + '$path');
            return r.ok ? [{ name: 'found', path: 'p' }] : $orElse;
          }
          async searchNovels(term, page) { return []; }
          async parseNovel(path) { return { name: 'n', path, chapters: [] }; }
          async parseChapter(path) { return ''; }
        }
        exports.default = new P();
    """.trimIndent()

    @Test fun aBlockThatLeavesNothingIsReportedAsABlock() = runBlocking<Unit> {
        PluginRuntime.load("blocked", plugin("blocked", "/blocked"), env).use { p ->
            val e = assertFailsWith<SiteBlockedException> { p.popular(1) }
            assertEquals("blocked", e.pluginId)
        }
    }

    @Test fun aPluginThatFailsOnTheBlockIsReportedAsABlockToo() = runBlocking<Unit> {
        PluginRuntime.load("blocked2", plugin("blocked2", "/blocked", "(() => { throw new Error('no list') })()"), env).use { p ->
            assertFailsWith<SiteBlockedException> { p.popular(1) }
        }
    }

    @Test fun aPluginThatGetsByAnywayIsLeftAlone() = runBlocking<Unit> {
        PluginRuntime.load("blocked3", plugin("blocked3", "/blocked", "[{ name: 'elsewhere', path: 'q' }]"), env).use { p ->
            assertEquals("elsewhere", p.popular(1).single().name)
        }
    }

    /** The block is often on the plain client alone: the browser is tried next,
     *  and the site's later pages go there from the start. */
    @Test fun aBlockedSiteIsAskedThroughTheBrowser() = runBlocking<Unit> {
        PluginRuntime.load("blocked4", plugin("blocked4", "/blocked"), env).use { p ->
            assertFailsWith<SiteBlockedException> { p.popular(1) }
        }
        SiteChecks.wantsBrowser(blockedUrl)  // the first ask is a plain retry, every half hour
        assertTrue(SiteChecks.wantsBrowser(blockedUrl), "the site should now go through the browser")
    }
}
