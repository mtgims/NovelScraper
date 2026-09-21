package com.novelscraper.app.net

import kotlinx.coroutines.flow.StateFlow

// Network helpers whose implementation depends on the platform. On Android they
// use a WebSocket plus offscreen WebViews and the system DownloadManager.

/**
 * Lets the server fetch pages through this device's own connection (sites that
 * block datacenter IPs). Connected while the app is in the foreground.
 */
expect object ScrapeRelay {
    val connected: StateFlow<Boolean>
    fun start()
    fun stop()
}

/**
 * Reads NovelUpdates pages with the user's NovelUpdates login. Returns an empty
 * series / null when it can't, and the caller falls back to the visible browser.
 */
expect object NuResolver {
    /** The series (title/author + groups), or empty groups if the page couldn't be
     *  read logged-in. */
    suspend fun extractSeries(seriesUrl: String): NuSeries

    /** Follow a group's /extnu/ link to the translator's URL, or null on failure. */
    suspend fun resolveExtnu(extnu: String): String?
}

/** Volume downloads (EPUB), saved where the user can open them. */
expect object Downloads {
    /** One volume as an EPUB. */
    fun volume(bookId: Int, slug: String, volume: Int, title: String)

    /** Every volume, zipped. */
    fun all(bookId: Int, slug: String, title: String)
}
