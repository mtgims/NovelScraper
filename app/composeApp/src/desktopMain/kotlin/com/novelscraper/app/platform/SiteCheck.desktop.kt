package com.novelscraper.app.platform

import com.novelscraper.app.extensions.Extensions
import dev.datlag.kcef.KCEF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
private const val TAG = "SiteCheck"

@Volatile private var ready = false
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
    if (!ensureBrowser(onStatus)) return false

    onStatus("Opening the site…")
    val client = withContext(Dispatchers.IO) { KCEF.newClientOrNull() }
    // Chromium reports a broken start on its own thread; don't open a blank
    // window and wait three minutes for nothing.
    if (client == null || org.cef.CefApp.getState() != org.cef.CefApp.CefAppState.INITIALIZED) {
        onStatus(failure ?: "The browser couldn't start on this system.")
        return false
    }
    val browser = client.createBrowser(url)
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
        val cookies = withTimeoutOrNull(WAIT_MS) {
            while (true) {
                if (!frame.isDisplayable) return@withTimeoutOrNull emptyList<Pair<String, String>>()
                val found = runCatching { readCookies(url) }.getOrDefault(emptyList())
                if (found.any { it.first == CLEARANCE }) return@withTimeoutOrNull found
                delay(700)
            }
            @Suppress("UNREACHABLE_CODE") emptyList<Pair<String, String>>()
        }
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

/** Fetch and start Chromium once; false if it couldn't be set up. */
private suspend fun ensureBrowser(onStatus: (String) -> Unit): Boolean {
    if (ready) return true
    return withContext(Dispatchers.IO) {
        try {
            KCEF.init(
                builder = {
                    installDir(File(appFilesDir(), "browser"))
                    progress {
                        onDownloading { percent ->
                            onStatus("Getting the browser ready… ${percent.toInt()}%")
                        }
                        onInitialized { onStatus("Browser ready.") }
                    }
                    settings {
                        cachePath = File(appCacheDir(), "browser").absolutePath
                        windowlessRenderingEnabled = false
                        // Sandboxing needs setuid helpers an AppImage doesn't have.
                        noSandbox = true
                    }
                    // Chromium picks its display backend itself and gives up if it
                    // guesses wrong, so it is told which one this session runs.
                    val wayland = System.getenv("WAYLAND_DISPLAY").orEmpty().isNotBlank()
                    // args() rather than addArgs(): the defaults JCEF works out
                    // already name a display backend, and the first one wins.
                    args(
                        if (wayland) "--ozone-platform=wayland" else "--ozone-platform=x11",
                        // Draw without a GPU: this window only runs a check, and GPU
                        // setups vary far more than software drawing does.
                        "--disable-gpu", "--disable-software-rasterizer", "--disable-dev-shm-usage",
                    )
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
            ready = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "couldn't set up the browser: ${e.message}")
            onStatus("Couldn't set up the browser: ${e.message}")
            false
        }
    }
}

/** Cookies the browser holds for [url]. */
private suspend fun readCookies(url: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
    val out = ArrayList<Pair<String, String>>()
    val done = java.util.concurrent.CountDownLatch(1)
    val visitor = object : org.cef.callback.CefCookieVisitor {
        override fun visit(cookie: CefCookie, count: Int, total: Int, delete: org.cef.misc.BoolRef): Boolean {
            out += cookie.name to cookie.value
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
    if (ready) runCatching { KCEF.disposeBlocking() }
}
