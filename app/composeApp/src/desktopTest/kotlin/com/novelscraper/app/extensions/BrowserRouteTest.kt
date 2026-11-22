package com.novelscraper.app.extensions

import com.novelscraper.app.platform.BrowserReply
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How a source's requests go to the browser and back: how often the browser is
 * asked, what is made of what it brings back, and when a site is let go back to
 * plain requests. A stand-in browser, since none is driven in tests.
 */
class BrowserRouteTest {

    /** The site's guard: up, every page is DDoS-Guard's 503 holding page. */
    private val guardUp = AtomicBoolean(true)
    private val plainAsked = AtomicInteger()

    private val holding = """
        <html><head><title>Just a moment...</title></head>
        <body>ddos-guard</body></html>
    """.trimIndent().toByteArray()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            plainAsked.incrementAndGet()
            val (code, body) = if (guardUp.get()) 503 to holding else 200 to "<ul><li>plain</li></ul>".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            ex.responseHeaders.add("Server", "ddos-guard")
            ex.sendResponseHeaders(code, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    private val site = "http://127.0.0.1:${server.address.port}"

    private val browserAsked = AtomicInteger()
    private var browserPage: (String) -> String? = { null }
    private var browserRequest: (String) -> BrowserReply? = { null }

    private val pageBefore = PluginRuntime.pageInBrowser
    private val requestBefore = PluginRuntime.requestInBrowser

    @BeforeTest fun standInBrowser() {
        SiteChecks.forget(site)
        PluginRuntime.pageInBrowser = { url, _, _ -> browserAsked.incrementAndGet(); browserPage(url) }
        PluginRuntime.requestInBrowser = { url, _, _, _, _ -> browserAsked.incrementAndGet(); browserRequest(url) }
    }

    @AfterTest fun restore() {
        PluginRuntime.pageInBrowser = pageBefore
        PluginRuntime.requestInBrowser = requestBefore
        SiteChecks.forget(site)
        server.stop(0)
    }

    private val env = PluginEnvironment(OkHttpClient(), "TestAgent/1.0", { mutableMapOf() }, allowPrivateHosts = true)

    /** A plugin whose list is [pages] fetched one after another (as Ranobes'
     *  chapter list is), each page's body becoming a novel's name. */
    private fun plugin(id: String, pages: Int = 1, method: String = "GET") = """
        class P {
          id = '$id'; name = 'P'; site = '$site'; version = '1.0.0';
          async popularNovels(page) {
            const { fetchApi } = require('@libs/fetch');
            const out = [];
            for (let i = 1; i <= $pages; i++) {
              const r = await fetchApi(this.site + '/list/' + i, { method: '$method', body: '$method' === 'POST' ? 'a=1' : undefined });
              if (r.ok) out.push({ name: await r.text(), path: 'p' + i });
            }
            return out;
          }
          async searchNovels(term, page) { return []; }
          async parseNovel(path) { return { name: 'n', path, chapters: [] }; }
          async parseChapter(path) { return ''; }
        }
        exports.default = new P();
    """.trimIndent()

    /** Ranobes' chapter list is many pages of one site. The browser that
     *  couldn't get past the check on the first one used to be sent at every
     *  other one too, each a full wait, so the reader heard about the check
     *  only after minutes, or the call ran out of time and never told them. */
    @Test fun aBrowserThatDidNotGetInIsNotAskedAgainInTheSameCall() = runBlocking<Unit> {
        PluginRuntime.load("route1", plugin("route1", pages = 5), env).use { p ->
            assertFailsWith<SiteChallengeException> { p.popular(1) }
        }
        assertEquals(1, browserAsked.get(), "the browser was asked once, not once per page")
    }

    /** A site written down as needing the browser is asked there first; when
     *  that fails, the plain request after it is refused, and the browser is
     *  not asked a second time for the same page. */
    @Test fun aSiteThatGoesStraightToTheBrowserIsNotAskedTwice() = runBlocking<Unit> {
        PluginRuntime.load("route2", plugin("route2"), env).use { p ->
            assertFailsWith<SiteChallengeException> { p.popular(1) }  // now written down
            browserAsked.set(0)
            plainAsked.set(0)
            assertFailsWith<SiteChallengeException> { p.popular(1) }  // straight to the browser
            assertEquals(1, browserAsked.get())
            assertEquals(1, plainAsked.get(), "then the plain request, once")
        }
    }

    /** A site's script request made from its page in the browser and refused
     *  there too was handed to the plugin as an answer, which it read as an
     *  empty list: "Nothing found" where a check was wanted. */
    @Test fun aRefusalFromInsideThePageIsACheckNotAnEmptyList() = runBlocking<Unit> {
        browserRequest = { BrowserReply(403, "<html><body>Access denied</body></html>") }
        PluginRuntime.load("route3", plugin("route3", method = "POST"), env).use { p ->
            assertFailsWith<SiteChallengeException> { p.popular(1) }
        }
    }

    /** DDoS-Guard's pass works for plain requests: once the browser has been
     *  let in, the next request goes plainly, and when it is answered the site
     *  stays plain. Each of Ranobes' chapter-list pages had been a page load in
     *  the browser, several seconds apiece. */
    @Test fun aPassThatCarriesPlainRequestsTakesTheSiteOffTheBrowser() = runBlocking<Unit> {
        browserPage = { guardUp.set(false); "<ul><li>browser</li></ul>" }
        PluginRuntime.load("route4", plugin("route4", pages = 4), env).use { p ->
            val names = p.popular(1).map { it.name }
            assertEquals(4, names.size)
            assertEquals(1, browserAsked.get(), "only the first page needed the browser: $names")
            assertFalse(SiteChecks.wantsBrowser("$site/list/1"), "the site is plain again")
        }
    }

    /** Cloudflare's pass holds only for the browser: the plain try after it is
     *  refused once, and not made again, or every other request would be. */
    @Test fun aPassThatHoldsOnlyForTheBrowserIsTriedPlainlyOnce() = runBlocking<Unit> {
        browserPage = { "<ul><li>browser</li></ul>" }
        PluginRuntime.load("route5", plugin("route5", pages = 5), env).use { p ->
            plainAsked.set(0)
            assertEquals(5, p.popular(1).size)
            // Page 1: refused plainly, then the browser. Page 2: the one plain try
            // after the pass, refused, then the browser. Pages 3 to 5: the browser.
            assertEquals(2, plainAsked.get(), "plain requests made")
            assertEquals(5, browserAsked.get())
            assertTrue(SiteChecks.wantsBrowser("$site/list/1"))
        }
    }

    /** A source that runs out of time says so. Its timeout used to come out as a
     *  cancellation, which every screen takes for the reader having left: the
     *  spinner stayed up and nothing was ever said. */
    @Test fun aCallThatRunsOutOfTimeIsAFailureNotACancellation() = runBlocking<Unit> {
        val previous = PluginRuntime.callTimeoutMs
        PluginRuntime.callTimeoutMs = 700
        PluginRuntime.pageInBrowser = { _, _, _ -> kotlinx.coroutines.delay(5_000); null }
        try {
            PluginRuntime.load("route6", plugin("route6"), env).use { p ->
                val e = runCatching { p.popular(1) }.exceptionOrNull()
                assertIs<PluginTimeoutException>(e)
            }
        } finally {
            PluginRuntime.callTimeoutMs = previous
        }
    }
}
