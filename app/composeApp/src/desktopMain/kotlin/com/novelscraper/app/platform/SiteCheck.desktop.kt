package com.novelscraper.app.platform

import com.novelscraper.app.extensions.BrowserCookieJar
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.platform.browserUserAgent
import dev.datlag.kcef.KCEF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.cef.browser.CefRendering
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.JFrame
import javax.swing.WindowConstants

/** The desktop app carries a browser (Chromium, fetched on first use). */
actual val canPassSiteChecks: Boolean = true

private const val CLEARANCE = "cf_clearance"
private const val WAIT_MS = 180_000L
/** How long a loaded page must stay loaded before its cookies are taken. */
private const val SETTLE_MS = 3_000L
private const val TAG = "SiteCheck"

@Volatile internal var browserReady = false
@Volatile private var failure: String? = null

/**
 * Opens the site in a real browser window so its check can run (and be clicked
 * through, when it asks), then hands the cookies it collected to the
 * extensions, which can carry on with ordinary requests.
 *
 * Chromium is downloaded the first time this is used, into the app's data
 * folder; [onStatus] reports that so the UI can explain the wait.
 */
actual suspend fun passSiteCheck(url: String, onStatus: (String) -> Unit): Boolean {
    val http = url.toHttpUrlOrNull() ?: return false
    // Without an X display there is nothing to put the browser in, and Chromium
    // would take the whole app down rather than fail.
    if (System.getenv("DISPLAY").orEmpty().isBlank()) {
        onStatus("The browser needs an X display (XWayland on a Wayland desktop).")
        return false
    }
    // The reader's own browser, when there is one: nothing to download, a
    // current version, and the graphics a check expects to find.
    if (SystemBrowser.available) {
        onStatus("Opening the site in ${SystemBrowser.binary?.name ?: "your browser"}…")
        val page = SystemBrowser.load(url, patienceMs = 3_000, interactiveMs = WAIT_MS, loadMs = 60_000) {
            onStatus("Answer the check in the window that just opened.")
        }
        val cookies = runCatching { SystemBrowser.cookies(url) }.getOrDefault(emptyList())
        if (page != null && cookies.isNotEmpty()) {
            Log.i(TAG, "system browser check: " + cookies.joinToString { "${'$'}{it.name}@${'$'}{it.domain}" })
            Extensions.cookies.acceptFromBrowser(http, cookies)
            onStatus("Done.")
            return true
        }
        Log.i(TAG, "the system browser didn't get through; trying the bundled one")
    }
    if (!ensureBrowser(onStatus)) return false

    onStatus("Opening the site…")
    val client = withContext(Dispatchers.IO) { KCEF.newClientOrNull() }
    // Chromium reports a broken start on its own thread; don't open a blank
    // window and wait three minutes for nothing.
    if (client == null || org.cef.CefApp.getState() != org.cef.CefApp.CefAppState.INITIALIZED) {
        onStatus(failure ?: "The browser couldn't start on this system.")
        return false
    }
    // Off-screen rendering: Chromium paints into a Java component instead of
    // owning an X11 window of its own. Embedding its window inside an AWT one
    // is what crashed (and, when it didn't, drew nothing).
    val browser = client.createBrowser(url, CefRendering.OFFSCREEN, false)
    val frame = withContext(Dispatchers.Main) {
        JFrame("Browser check").apply {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            layout = BorderLayout()
            add(browser.uiComponent, BorderLayout.CENTER)
            size = Dimension(900, 720)
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
    // The native browser can only be built once its window is on screen.
    withContext(Dispatchers.Main) { runCatching { browser.createImmediately() } }
    try {
        val cookies: List<BrowserCookieJar.BrowserCookie>? = withTimeoutOrNull(WAIT_MS) {
            var settledSince = 0L
            while (true) {
                // The reader closed the window: take whatever it collected.
                if (!frame.isDisplayable) {
                    return@withTimeoutOrNull runCatching { readCookies(url) }
                        .getOrDefault(emptyList<BrowserCookieJar.BrowserCookie>())
                }
                val found = runCatching { readCookies(url) }.getOrDefault(emptyList<BrowserCookieJar.BrowserCookie>())
                // A passed Cloudflare check leaves this behind.
                if (found.any { it.name == CLEARANCE }) return@withTimeoutOrNull found
                // Some sites let us through without one: the page finished loading
                // and stayed loaded, so whatever it set is what we need.
                val loading = runCatching { browser.isLoading }.getOrDefault(true)
                if (!loading && found.isNotEmpty()) {
                    if (settledSince == 0L) settledSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - settledSince > SETTLE_MS) return@withTimeoutOrNull found
                } else {
                    settledSince = 0L
                }
                delay(700)
            }
            @Suppress("UNREACHABLE_CODE") emptyList<BrowserCookieJar.BrowserCookie>()
        }
        Log.i(TAG, "browser check: " + cookies.orEmpty().joinToString { "${it.name}@${it.domain}" })
        if (cookies.isNullOrEmpty()) {
            onStatus("The check didn't pass.")
            return false
        }
        Extensions.cookies.acceptFromBrowser(http, cookies)
        onStatus("Done.")
        return true
    } finally {
        withContext(Dispatchers.Main) { frame.dispose() }
        runCatching { browser.close(true) }
        runCatching { client.dispose() }
    }
}

/** Fetch and start Chromium once; false if it couldn't be set up. Shared with
 *  [fetchThroughBrowser]. */
internal suspend fun ensureSiteCheckBrowser(onStatus: (String) -> Unit): Boolean = ensureBrowser(onStatus)

private suspend fun ensureBrowser(onStatus: (String) -> Unit): Boolean {
    if (browserReady) return true
    return withContext(Dispatchers.IO) {
        try {
            val install = File(appFilesDir(), "browser")
            KCEF.init(
                builder = {
                    installDir(install)
                    progress {
                        onDownloading { percent ->
                            onStatus("Getting the browser ready… ${percent.toInt()}%")
                        }
                        onInitialized { onStatus("Browser ready.") }
                    }
                    settings {
                        cachePath = File(appCacheDir(), "browser").absolutePath
                        windowlessRenderingEnabled = true
                        // Sandboxing needs setuid helpers an AppImage doesn't have.
                        noSandbox = true
                        // Chromium's own helper processes and resources live in the
                        // downloaded bundle, not beside the app's Java runtime, which
                        // is where it looks by default: without these it can't start
                        // its helpers and takes the app down with it.
                        // The same browser the app's own requests claim to be: a
                        // clearance cookie is tied to the user agent that earned it.
                        userAgent = browserUserAgent
                        browserSubProcessPath = File(install, "jcef_helper").absolutePath
                        resourcesDirPath = install.absolutePath
                        localesDirPath = File(install, "locales").absolutePath
                    }
                    // X11 always: the browser is put inside an AWT window, and those
                    // are X11 even on a Wayland desktop (through XWayland). A Wayland
                    // surface in an X11 window crashes Chromium outright.
                    // args() rather than addArgs(): the defaults JCEF works out
                    // already name a display backend, and the first one wins.
                    args(
                        "--ozone-platform=x11",
                        "--disable-dev-shm-usage",
                    )
                    // The GPU is deliberately left on: a browser that reports no
                    // WebGL looks like a bot, and these checks are exactly what
                    // that judgement is for.
                },
                onError = {
                    failure = "The browser couldn't start: ${it?.message ?: "unknown error"}"
                    Log.w(TAG, "browser: ${it?.message}")
                },
            )
            // CefApp reaches INITIALIZED on its own thread shortly after init.
            repeat(40) {
                if (org.cef.CefApp.getState() == org.cef.CefApp.CefAppState.INITIALIZED) return@repeat
                Thread.sleep(250)
            }
            if (org.cef.CefApp.getState() != org.cef.CefApp.CefAppState.INITIALIZED) {
                onStatus(failure ?: "The browser couldn't start on this system.")
                return@withContext false
            }
            browserReady = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "couldn't set up the browser: ${e.message}")
            onStatus("Couldn't set up the browser: ${e.message}")
            false
        }
    }
}

/** Cookies the browser holds for [url], as it holds them. */
internal suspend fun readCookies(url: String): List<BrowserCookieJar.BrowserCookie> = withContext(Dispatchers.IO) {
    val out = ArrayList<BrowserCookieJar.BrowserCookie>()
    val done = java.util.concurrent.CountDownLatch(1)
    val visitor = object : org.cef.callback.CefCookieVisitor {
        override fun visit(cookie: CefCookie, count: Int, total: Int, delete: org.cef.misc.BoolRef): Boolean {
            out += BrowserCookieJar.BrowserCookie(
                name = cookie.name, value = cookie.value, domain = cookie.domain, path = cookie.path,
                expiresAt = cookie.expires?.time ?: 0L, secure = cookie.secure, httpOnly = cookie.httponly,
            )
            if (count + 1 >= total) done.countDown()
            return true
        }
    }
    if (!CefCookieManager.getGlobalManager().visitUrlCookies(url, true, visitor)) return@withContext emptyList()
    done.await(3, java.util.concurrent.TimeUnit.SECONDS)
    out
}

/** Let go of the browser when the app closes. */
fun disposeSiteCheckBrowser() {
    disposeFetchBrowser()   // also closes the system browser, if one was driven
    if (browserReady) runCatching { KCEF.disposeBlocking() }
}
