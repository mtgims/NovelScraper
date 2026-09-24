package com.novelscraper.app.platform

import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.extensions.SiteChecks
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering
import org.cef.callback.CefStringVisitor
import java.awt.Dimension
import javax.swing.JFrame
import javax.swing.WindowConstants

/** The browser on this computer, or the one the app fetched for itself. */
actual val canFetchThroughBrowser: Boolean
    get() = SystemBrowser.available || browserReady ||
        java.io.File(appFilesDir(), "browser/install.lock").exists()

private val lock = Mutex()
private const val LOAD_MS = 90_000L
private const val SETTLE_MS = 2_000L
/** How long a check gets to pass by itself before the window is shown. */
private const val PATIENCE_MS = 6_000L
/** The same, for a site whose check has wanted a person before. */
private const val IMPATIENCE_MS = 2_500L
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
    // The reader's own browser first: it is current, it has the graphics card
    // behind it, and checks that turn the bundled one away pass in it.
    if (SystemBrowser.available) {
        // A site that has needed a person before is given a few seconds rather
        // than the usual wait: long enough for a check it has already been
        // passed to go through unseen, short enough not to be a silent minute
        // spent on one that was never going to pass alone.
        val patience = if (SiteChecks.wantsPerson(url)) IMPATIENCE_MS else PATIENCE_MS
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        var askedPerson = false
        val page = SystemBrowser.load(url, patience, INTERACTIVE_MS, LOAD_MS) {
            askedPerson = true
            SiteChecks.neededPerson(url)
            // The window appearing by itself explains nothing on its own.
            showToast("$host is asking for a browser check: answer it in the window that just opened.", long = true)
        }
        if (page != null) {
            // It let the app through without anyone lifting a finger: the site
            // stops being one whose window comes up on sight.
            if (!askedPerson) SiteChecks.passedAlone(url)
            keepSystemCookies(url)
            Log.i(TAG, "$url -> ${page.length} chars (${SystemBrowser.binary?.name})")
            return page
        }
        // No falling back to the carried browser: it is Chrome 126, it passes
        // nothing this one couldn't, and firing up a second browser window on
        // top of the one the reader just closed only adds insult.
        Log.i(TAG, "the browser didn't get the page")
        return null
    }
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
        // A page that came through carries the cookies that got it through: hand
        // them to the extensions so the next pages can be fetched plainly, at the
        // speed of a request rather than a page load.
        if (result != null) keepCookies(url)
        Log.i(TAG, "$url -> ${result?.length ?: -1} chars")
        result
    } finally {
        if (shown) withContext(Dispatchers.Main) { hideFrame() }
    }
}

/** The same, for the browser this computer already had. */
private suspend fun keepSystemCookies(url: String) {
    val http = url.toHttpUrlOrNull() ?: return
    val cookies = runCatching { SystemBrowser.cookies(url) }.getOrDefault(emptyList())
    if (cookies.isEmpty()) return
    // Filing them away is bookkeeping: if it goes wrong, the page we just went
    // to all that trouble for is still a page, and it is not thrown away for it.
    runCatching { Extensions.cookies.acceptFromBrowser(http, cookies) }
        .onSuccess { Log.i(TAG, "kept ${cookies.size} cookies for ${http.host}") }
        .onFailure { Log.w(TAG, "couldn't keep cookies for ${http.host}: ${it.message}") }
}

/** Give what the browser collected to the extensions' cookie jar. */
private suspend fun keepCookies(url: String) {
    val http = url.toHttpUrlOrNull() ?: return
    val cookies = runCatching { readCookies(url) }.getOrDefault(emptyList())
    if (cookies.isEmpty()) return
    runCatching { Extensions.cookies.acceptFromBrowser(http, cookies) }
        .onSuccess { Log.i(TAG, "kept ${cookies.size} cookies for ${http.host}") }
        .onFailure { Log.w(TAG, "couldn't keep cookies for ${http.host}: ${it.message}") }
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
    runCatching { SystemBrowser.dispose() }
    runCatching { frame?.dispose() }
    runCatching { browser?.close(true) }
    runCatching { client?.dispose() }
    frame = null
    browser = null
    client = null
}

private const val OFFSCREEN_X = -3000
private const val OFFSCREEN_Y = -3000

/** A request made from inside the page, through the browser already driving it. */
actual suspend fun requestThroughBrowser(
    url: String,
    method: String,
    headers: Map<String, String>,
    body: BrowserBody?,
): BrowserReply? = lock.withLock {
    if (!SystemBrowser.available) return null
    val reply = SystemBrowser.request(url, method, headers, body)
    if (reply != null) keepSystemCookies(url)
    reply
}
