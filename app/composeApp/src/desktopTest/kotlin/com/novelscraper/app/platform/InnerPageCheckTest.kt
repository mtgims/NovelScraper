package com.novelscraper.app.platform

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A site that lets its front page through and holds only its chapters behind
 * a check that passes by itself after a few seconds, as FuckNovelpia does with
 * Cloudflare's managed check on chapter.php. Drives the browser on this
 * machine, so it runs only when asked:
 * `./gradlew :composeApp:desktopTest --tests '*InnerPageCheckTest*' -PliveBrowser=on`.
 */
class InnerPageCheckTest {
    private val live = !System.getProperty("live.browser").isNullOrBlank()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val passed = ex.requestHeaders["Cookie"].orEmpty().any { "pass=1" in it }
            val (code, body) = when {
                // A check that never passes by itself: it wants a person.
                ex.requestURI.path == "/guarded" ->
                    403 to "<html><head><title>Just a moment...</title></head><body><p>Checking</p></body></html>"
                ex.requestURI.path != "/chapter" ->
                    200 to "<html><head><title>Home</title></head><body><p>front page</p></body></html>"
                passed -> 200 to "<html><head><title>Chapter 1</title></head><body><p>the chapter text</p></body></html>"
                // The check: a few seconds' work in a page of its own (not in a
                // frame), then a cookie and a reload into the chapter.
                else -> 403 to """<html><head><title>Just a moment...</title></head><body><p>Checking</p>
                    <script>if (top === self) setTimeout(function () {
                      document.cookie = 'pass=1; path=/'; location.reload();
                    }, 5000);</script></body></html>"""
            }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html")
            ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @AfterTest fun stop() {
        server.stop(0)
        SystemBrowser.mayShow = true
        SystemBrowser.endItBehindOurBack()
        // Until it is gone it still looks alive, and the next test would reach
        // for it (LiveBrowserTest waits the same).
        if (live) Thread.sleep(2_000)
    }

    @Test fun aPageNobodyAskedForGivesUpRatherThanShowItsCheck() = runBlocking<Unit> {
        if (!live) return@runBlocking
        // On screen if anything asks for it: the test is that nothing does.
        SystemBrowser.mayShow = true
        var shown = false
        val started = System.currentTimeMillis()
        val page = SystemBrowser.load(
            "http://127.0.0.1:${server.address.port}/guarded",
            patienceMs = 3_000, interactiveMs = 60_000, loadMs = 90_000, unattended = true,
        ) { shown = true }
        assertTrue(page == null, "no page past a check nobody answered")
        assertTrue(!shown, "and no window put up for it")
        assertTrue(System.currentTimeMillis() - started < 60_000, "given up once a person was wanted, not at the deadline")
    }

    @Test fun aCheckOnlyOnTheChapterIsWaitedOut() = runBlocking<Unit> {
        if (!live) return@runBlocking
        SystemBrowser.mayShow = false    // nothing on the screen; this check passes alone
        val url = "http://127.0.0.1:${server.address.port}/chapter"
        val page = SystemBrowser.load(url, patienceMs = 6_000, interactiveMs = 60_000, loadMs = 60_000)
        assertTrue(page != null && "the chapter text" in page, "the chapter, past its check: ${page?.take(200)}")
    }
}
