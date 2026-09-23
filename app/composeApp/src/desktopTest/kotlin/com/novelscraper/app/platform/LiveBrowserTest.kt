package com.novelscraper.app.platform

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drives the browser on this machine against a real site. Off by default (it
 * needs a display and the network); run it with
 * `./gradlew :composeApp:desktopTest -PliveBrowser=https://example.com`.
 */
class LiveBrowserTest {

    private val target: String? = System.getProperty("live.browser")?.takeIf { it.isNotBlank() }

    @Test
    fun loadsAPageInTheSystemBrowser() {
        val url = target ?: return
        println("browser: ${SystemBrowser.binary?.path}")
        assertTrue(SystemBrowser.available, "no Chromium-family browser found on PATH")
        runBlocking {
            val page = SystemBrowser.load(url, patienceMs = 8_000, interactiveMs = 90_000, loadMs = 120_000)
            println("user agent: ${SystemBrowser.userAgent}")
            println("page: ${page?.length ?: -1} chars")
            System.getProperty("live.dump")?.takeIf { it.isNotBlank() }?.let { path ->
                page?.let { java.io.File(path).writeText(it); println("dumped to $path") }
            }
            println(page?.take(1600))
            val cookies = SystemBrowser.cookies(url)
            println("cookies: " + cookies.joinToString { "${it.name}@${it.domain}" })
            if (page == null) {
                val stuck = SystemBrowser.currentPage()
                println("--- stuck on (${stuck?.length ?: -1} chars) ---")
                println(stuck?.take(1200))
            }
            // What the app's own requests would now get, carrying what the browser earned.
            val jar = com.novelscraper.app.extensions.BrowserCookieJar()
            jar.acceptFromBrowser(url.toHttpUrl(), cookies)
            val client = okhttp3.OkHttpClient.Builder().cookieJar(jar).build()
            client.newCall(
                okhttp3.Request.Builder().url(url)
                    .header("User-Agent", SystemBrowser.userAgent.orEmpty())
                    .build(),
            ).execute().use { r -> println("plain request with that clearance: HTTP ${r.code}") }
            SystemBrowser.dispose()
            assertTrue(page != null && page.length > 200, "nothing came back from the browser")
        }
    }
}

/**
 * Fetches a current Chrome the way a machine with no browser would, and drives
 * it. Run with `./gradlew :composeApp:desktopTest -PfetchBrowser=true`.
 */
class LiveChromeDownloadTest {

    @Test
    fun fetchesAndDrivesAChromeOfItsOwn() {
        if (System.getProperty("fetch.browser") != "true") return
        runBlocking {
            val chrome = ChromeDownload.ensure { println("  $it") }
            println("fetched: ${chrome?.path}")
            assertTrue(chrome != null && chrome.canExecute(), "no browser came back")
        }
    }
}
