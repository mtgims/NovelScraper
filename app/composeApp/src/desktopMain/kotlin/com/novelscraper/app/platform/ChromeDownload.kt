package com.novelscraper.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A current Chrome, fetched for machines that have no Chromium-family browser of
 * their own.
 *
 * Google publishes a build of each Chrome release for exactly this, driving a
 * browser from a program, and says where to find it in a small index. It is the
 * same browser a site's check expects to meet, at the version it expects to
 * meet it at, which the Chromium the app used to carry (Chrome 126, two years
 * old) is not.
 *
 * It lands in the app's data folder, is used by [SystemBrowser] exactly as an
 * installed browser would be, and is fetched once.
 */
object ChromeDownload {

    private const val TAG = "ChromeDownload"
    private const val INDEX =
        "https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val home = File(appFilesDir(), "chrome")

    /** What Google calls this platform, and where the program sits inside. */
    private val platform = if (Os.isWindows) "win64" else "linux64"
    private val inside = if (Os.isWindows) "chrome-win64/chrome.exe" else "chrome-linux64/chrome"

    /** The downloaded browser, if one is already here. */
    val installed: File?
        get() = home.listFiles().orEmpty()
            .mapNotNull { File(it, inside).takeIf { f -> f.canExecute() } }
            .maxByOrNull { it.parentFile.parentFile.name }

    /**
     * Makes sure a browser is here, fetching one if not, and hands back its
     * program. [onStatus] carries the wait to the UI, which is a couple of
     * hundred megabytes long.
     */
    suspend fun ensure(onStatus: (String) -> Unit): File? = withContext(Dispatchers.IO) {
        installed?.let { return@withContext it }
        val (version, url) = index() ?: run {
            onStatus("Couldn't find out where to get a browser.")
            return@withContext null
        }
        val target = File(home, version).apply { mkdirs() }
        val zip = File(target, "chrome.zip")
        try {
            onStatus("Getting a browser ready… 0%")
            if (!download(url, zip, onStatus)) return@withContext null
            onStatus("Unpacking the browser…")
            unzip(zip, target)
            zip.delete()
            val chrome = File(target, inside)
            if (!chrome.isFile) {
                onStatus("The browser didn't unpack as expected.")
                return@withContext null
            }
            onStatus("Browser ready.")
            Log.i(TAG, "fetched Chrome $version")
            chrome
        } catch (e: Exception) {
            Log.w(TAG, "couldn't fetch a browser: ${e.message}")
            onStatus("Couldn't fetch a browser: ${e.message}")
            target.deleteRecursively()
            null
        }
    }

    /** The current stable version and where its Linux build lives. */
    private fun index(): Pair<String, String>? {
        val body = runCatching {
            http.newCall(Request.Builder().url(INDEX).build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        }.getOrNull() ?: return null
        val stable = runCatching {
            json.parseToJsonElement(body).jsonObject["channels"]?.jsonObject?.get("Stable")?.jsonObject
        }.getOrNull() ?: return null
        val version = stable["version"]?.jsonPrimitive?.contentOrNull ?: return null
        val url = stable["downloads"]?.jsonObject?.get("chrome")?.jsonArray
            ?.firstOrNull { it.jsonObject["platform"]?.jsonPrimitive?.contentOrNull == platform }
            ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull ?: return null
        return version to url
    }

    private fun download(url: String, into: File, onStatus: (String) -> Unit): Boolean {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body ?: return false
            if (!response.isSuccessful) return false
            val total = body.contentLength()
            var read = 0L
            var reported = -1
            body.byteStream().use { input ->
                into.outputStream().buffered().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt()
                            if (percent != reported) {
                                reported = percent
                                onStatus("Getting a browser ready… $percent%")
                            }
                        }
                    }
                }
            }
        }
        return into.length() > 0
    }

    /** Unpacked with the permissions the archive carries: a browser is a program
     *  plus a handful of helper programs, and none of them run without them. */
    private fun unzip(zip: File, into: File) {
        ZipFile.Builder().setFile(zip).get().use { archive ->
            val entries = archive.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val out = File(into, entry.name)
                if (!out.canonicalPath.startsWith(into.canonicalPath + File.separator)) continue
                if (entry.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    out.outputStream().buffered().use { input.copyTo(it) }
                }
                if (entry.unixMode and 0b001_000_000 != 0) out.setExecutable(true, false)
            }
        }
    }
}
