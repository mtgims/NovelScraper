package com.novelscraper.app.platform

import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFBrowser
import dev.datlag.kcef.KCEFClient
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
import javax.swing.WindowConstants

/** The desktop app carries Chromium, but only once it has been fetched. */
actual val canFetchThroughBrowser: Boolean
    get() = browserReady || java.io.File(appFilesDir(), "browser/install.lock").exists()

private val lock = Mutex()
private const val LOAD_MS = 90_000L
private const val SETTLE_MS = 2_000L
/** How long a check gets to pass by itself before the window is shown. */
private const val PATIENCE_MS = 12_000L
/** How long the reader then has to answer a check that wants a tap. */
private const val INTERACTIVE_MS = 5 * 60_000L
private const val TAG = "BrowserFetch"

// One browser for the whole run: a solved check, and the cookies behind it, then
// hold for every page after it, the way it would in a browser you keep open.
private var client: KCEFClient? = null
private var browser: KCEFBrowser? = null
private var frame: JFrame? = null

/**
 * Loads a page in Chromium and hands back what it ends up with, for sites that
 * refuse plain requests however good the cookies are.
 *
 * The window stays out of the way (parked off-screen) while the site behaves.
 * If it answers with a check that hasn't passed after [PATIENCE_MS], the window
 * is brought on screen: some checks only pass for a browser that is really being
 * drawn, and some want a tick in a box. It goes away again once the page comes
 * through. One page at a time, so a chapter download doesn't open twelve.
 */
actual suspend fun fetchThroughBrowser(url: String): String? = lock.withLock {
    if (!ensureSiteCheckBrowser { }) return null
    val view = open() ?: return null
    withContext(Dispatchers.Main) { view.loadURL(url) }
    var shown = false
    try {
        val started = System.currentTimeMillis()
        // Once the window is up, the clock is the reader's, not ours: a check
        // that wants a tick in a box waits for the person to give it.
        var deadline = started + LOAD_MS
        while (runCatching { view.isLoading }.getOrDefault(true) && System.currentTimeMillis() < deadline) delay(300)
        delay(SETTLE_MS)
        var page = source(view)
        while (page != null && looksLikeBrowserCheck(page) && System.currentTimeMillis() < deadline) {
            if (!shown && System.currentTimeMillis() - started > PATIENCE_MS) {
                shown = true
                Log.i(TAG, "the check needs a visible browser; showing it")
                withContext(Dispatchers.Main) { showFrame() }
                deadline = System.currentTimeMillis() + INTERACTIVE_MS
            }
            delay(1_500)
            page = source(view)
        }
        val result = page?.takeIf { !looksLikeBrowserCheck(it) }
        Log.i(TAG, "$url -> ${result?.length ?: -1} chars")
        result
    } finally {
        if (shown) withContext(Dispatchers.Main) { hideFrame() }
    }
}

/** The shared browser, made on first use. */
private suspend fun open(): KCEFBrowser? {
    browser?.let { return it }
    val c = withContext(Dispatchers.IO) { KCEF.newClientOrNull() } ?: return null
    client = c
    // Off-screen rendering: Chromium paints into a Java component rather than
    // owning a window of its own, which is what an AWT window can hold.
    val view = c.createBrowser("about:blank", CefRendering.OFFSCREEN, false)
    browser = view
    withContext(Dispatchers.Main) {
        frame = JFrame("Browser check").apply {
            defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
            add(view.uiComponent)
            size = Dimension(1000, 780)
            setLocation(OFFSCREEN_X, OFFSCREEN_Y)
            isVisible = true          // mapped, so Chromium draws; just not where anyone looks
        }
        runCatching { view.createImmediately() }
    }
    return view
}

private fun showFrame() {
    frame?.apply {
        title = "Browser check"
        setLocationRelativeTo(null)
        toFront()
        requestFocus()
    }
}

private fun hideFrame() {
    frame?.setLocation(OFFSCREEN_X, OFFSCREEN_Y)
}

/** The page's HTML, as the browser has it now. */
private suspend fun source(browser: CefBrowser): String? {
    val html = CompletableDeferred<String?>()
    browser.getSource(CefStringVisitor { value -> html.complete(value?.takeIf { it.isNotBlank() }) })
    return withTimeoutOrNull(10_000) { html.await() }
}

/** Let go of the browser when the app closes. */
internal fun disposeFetchBrowser() {
    runCatching { frame?.dispose() }
    runCatching { browser?.close(true) }
    runCatching { client?.dispose() }
    frame = null
    browser = null
    client = null
}

private const val OFFSCREEN_X = -3000
private const val OFFSCREEN_Y = -3000
