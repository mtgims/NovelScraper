package com.novelscraper.app.platform

import dev.datlag.kcef.KCEF
import com.novelscraper.app.platform.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering
import org.cef.callback.CefStringVisitor
import java.awt.Dimension
import javax.swing.JFrame

/**
 * True once Chromium is on this computer. Until then a site that needs it is
 * offered the browser check, which explains the one-time download first: half a
 * gigabyte must never arrive unasked.
 */
actual val canFetchThroughBrowser: Boolean
    get() = browserReady || java.io.File(appFilesDir(), "browser/install.lock").exists()

private val lock = Mutex()
private const val LOAD_MS = 60_000L
private const val SETTLE_MS = 2_500L

/**
 * Loads the page in Chromium and hands back what it ends up with. The browser
 * is off-screen: a site that answers a plain request with a browser check gets
 * an actual browser, and the reader sees nothing.
 *
 * One page at a time (a chapter download would otherwise start a dozen).
 */
actual suspend fun fetchThroughBrowser(url: String): String? = lock.withLock {
    if (!ensureSiteCheckBrowser { }) return null
    val client = withContext(Dispatchers.IO) { KCEF.newClientOrNull() } ?: return null
    val browser = client.createBrowser(url, CefRendering.OFFSCREEN, false)
    // Off-screen rendering still wants a window to belong to; it is never shown.
    val frame = withContext(Dispatchers.Main) {
        JFrame("NovelScraper").apply {
            isUndecorated = true
            add(browser.uiComponent)
            size = Dimension(1280, 900)
            setLocation(-4000, -4000)
            isVisible = true
        }
    }
    try {
        withContext(Dispatchers.Main) { runCatching { browser.createImmediately() } }
        val html = withTimeoutOrNull(LOAD_MS) {
            while (runCatching { browser.isLoading }.getOrDefault(true)) delay(300)
            delay(SETTLE_MS)
            var page = source(browser)
            // The site may answer with its check first and swap in the real page
            // once the browser has run it, which takes a few seconds.
            while (page != null && looksLikeBrowserCheck(page)) {
                delay(1_500)
                page = source(browser)
            }
            page
        }
        Log.i("BrowserFetch", "$url -> ${html?.length ?: -1} chars; title=" +
            (html?.let { Regex("<title[^>]*>(.{0,90})").find(it)?.groupValues?.get(1) } ?: "none"))
        html
    } finally {
        withContext(Dispatchers.Main) { frame.dispose() }
        runCatching { browser.close(true) }
        runCatching { client.dispose() }
    }
}

/** The page's HTML, as the browser has it now. */
private suspend fun source(browser: CefBrowser): String? {
    val html = CompletableDeferred<String?>()
    browser.getSource(CefStringVisitor { value -> html.complete(value?.takeIf { it.isNotBlank() }) })
    return withTimeoutOrNull(10_000) { html.await() }
}
