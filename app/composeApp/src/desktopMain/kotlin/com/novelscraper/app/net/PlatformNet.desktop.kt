package com.novelscraper.app.net

import com.novelscraper.app.platform.DesktopDirs
import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The scrape relay on desktop: plain fetches from this computer's connection.
 *  There is no embedded browser, so pages that need one are left to the server. */
actual object ScrapeRelay {
    private const val UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    private val client = RelayClient(UA, renderer = null)

    actual val connected: StateFlow<Boolean> get() = client.connected
    actual fun start() = client.start()
    actual fun stop() = client.stop()
}

/** Reading NovelUpdates needs the user's logged-in browser session, which only
 *  the Android app has; the scrape screen doesn't offer it on desktop. */
actual object NuResolver {
    actual suspend fun extractSeries(seriesUrl: String): NuSeries = NuSeries()
    actual suspend fun resolveExtnu(extnu: String): String? = null
}

/**
 * Volume downloads into the user's Downloads folder, through the shared client
 * (the endpoints need the session cookie). Written to a ".part" file and moved
 * into place when complete; never overwrites an existing file ("name (1).epub").
 */
actual object Downloads {
    private const val TAG = "Downloads"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    actual fun volume(bookId: Int, slug: String, volume: Int, title: String) =
        download("${Net.baseUrl}api/books/$bookId/download?volume=$volume",
            downloadFileName(slug, volume), "$title · volume $volume")

    actual fun all(bookId: Int, slug: String, title: String) =
        download("${Net.baseUrl}api/books/$bookId/download-all",
            downloadFileName(slug), "$title · all volumes")

    private fun download(url: String, fileName: String, label: String) {
        scope.launch {
            var target: File? = null
            var part: File? = null
            try {
                val dir = DesktopDirs.downloads.apply { mkdirs() }
                target = reserve(dir, fileName)
                part = File(dir, "${target.name}.part")
                Net.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("empty response")
                    body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                }
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                showToast("Saved $label to ${target.path}", long = true)
            } catch (e: Exception) {
                Log.w(TAG, "download failed: ${e.message}")
                part?.delete()
                target?.takeIf { it.length() == 0L }?.delete()
                showToast("Couldn't download $label (${e.message})", long = true)
            }
        }
    }

    /** Claims the first free name among "name.ext", "name (1).ext", ... by creating
     *  it empty, so two downloads of the same volume never pick the same file. */
    internal fun reserve(dir: File, fileName: String): File {
        val stem = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 0
        while (true) {
            val candidate = File(dir, if (n == 0) fileName else "$stem ($n)$ext")
            if (candidate.createNewFile()) return candidate
            n++
        }
    }
}
