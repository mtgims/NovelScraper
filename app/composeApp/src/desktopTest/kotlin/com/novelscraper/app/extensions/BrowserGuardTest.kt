package com.novelscraper.app.extensions

import com.novelscraper.app.platform.SystemBrowser
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * A source can't reach the local network through the browser either. Drives
 * the browser on this machine, so it runs only when asked:
 * `./gradlew :composeApp:desktopTest --tests '*BrowserGuardTest*' -PliveBrowser=on`.
 */
class BrowserGuardTest {
    private val live = !System.getProperty("live.browser").isNullOrBlank()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = "<html><body><p>local secret</p></body></html>".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        start()
    }
    private val port = server.address.port

    @AfterTest fun stop() {
        server.stop(0)
        SystemBrowser.extraFlags = emptyList()
        SystemBrowser.endItBehindOurBack()
    }

    private val walled = PluginEnvironment(OkHttpClient(), "TestAgent/1.0", { mutableMapOf() })

    /** A source that asks for [url] twice: a site needing the browser is tried
     *  plainly once in a while, so the second ask is the one the browser takes. */
    private fun prober(url: String) = """
        class Prober {
          id = 'prober'; name = 'Prober'; site = '$url'; version = '1.0.0';
          async popularNovels(page) {
            const { fetchApi } = require('@libs/fetch');
            let text = 'nothing';
            for (let i = 0; i < 2; i++) {
              try { text = await (await fetchApi('$url')).text(); } catch (e) {}
            }
            return [{ name: text, path: 'p' }];
          }
          async searchNovels(term, page) { return []; }
          async parseNovel(path) { return { name: 'n', path, chapters: [] }; }
          async parseChapter(path) { return ''; }
        }
        exports.default = new Prober();
    """.trimIndent()

    private suspend fun whatTheSourceRead(url: String): String =
        PluginRuntime.load("prober", prober(url), walled).use { p ->
            runCatching { p.popular(1).single().name }.getOrElse { "failed: ${it.message}" }
        }

    @Test fun aLocalAddressMarkedForTheBrowserIsNotLoaded() = runBlocking<Unit> {
        if (!live) return@runBlocking
        val url = "http://127.0.0.1:$port/page"
        SiteChecks.needsBrowser(url)
        val read = whatTheSourceRead(url)
        assertFalse("local secret" in read, "the source read the local page through the browser: $read")
    }

    /** The browser looks names up for itself, so a name the app found public can
     *  still land on this machine there. What the page came from is what counts. */
    @Test fun aNameTheBrowserResolvesToThisMachineIsNotHandedBack() = runBlocking<Unit> {
        if (!live) return@runBlocking
        SystemBrowser.extraFlags = listOf("--host-resolver-rules=MAP rebind.invalid 127.0.0.1")
        SystemBrowser.endItBehindOurBack()   // start afresh, with the rule
        val url = "http://rebind.invalid:$port/page"
        SiteChecks.needsBrowser(url)
        val read = whatTheSourceRead(url)
        assertFalse("local secret" in read, "the source read the local page through the browser: $read")
    }
}
