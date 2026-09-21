package com.novelscraper.app.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual object ScrapeRelay {
    actual val connected: StateFlow<Boolean> = MutableStateFlow(false)
    actual fun start(): Unit = TODO("Phase 2")
    actual fun stop(): Unit = TODO("Phase 2")
}

actual object NuResolver {
    actual suspend fun extractSeries(seriesUrl: String): NuSeries = TODO("Phase 2")
    actual suspend fun resolveExtnu(extnu: String): String? = TODO("Phase 2")
}

actual object Downloads {
    actual fun volume(bookId: Int, slug: String, volume: Int, title: String): Unit = TODO("Phase 2")
    actual fun all(bookId: Int, slug: String, title: String): Unit = TODO("Phase 2")
}
