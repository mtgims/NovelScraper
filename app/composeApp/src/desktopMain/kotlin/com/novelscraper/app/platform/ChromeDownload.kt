package com.novelscraper.app.platform

import com.novelscraper.app.extensions.compareVersions
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
 * It lands in the app's data folder and is used by [SystemBrowser] exactly as
 * an installed browser would be. It browses whatever sites the reader's
 * sources do, so it is kept current: checked for a newer build at most weekly
 * ([updateLater]), the new one used from the next start, the old one removed.
 */
object ChromeDownload {

    private const val TAG = "ChromeDownload"
    private const val PARTIAL = ".partial"
    private const val UPDATE_EVERY_MS = 7L * 24 * 60 * 60 * 1000
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

    /** The downloaded browser, if one is already here: the newest, if several.
     *  Versions are compared as numbers; as text, "99" came after "156". */
    val installed: File?
        get() = versions().maxWithOrNull { a, b -> compareVersions(a.name, b.name) }
            ?.let { File(it, inside) }

    /** The version folders holding a browser that can run. */
    private fun versions(): List<File> = home.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.endsWith(PARTIAL) && File(it, inside).canExecute() }

    /** When a newer build was last looked for (a file's time, so it needs no settings). */
    private val checked get() = File(home, ".checked")

    /**
     * Keep the downloaded browser current: at most once a week, look for a newer
     * stable build and fetch it in the background; it is used from the next
     * start. Older versions are removed now, before this start uses the newest.
     */
    fun updateLater() {
        removeOlder()
        if (System.currentTimeMillis() - checked.lastModified() < UPDATE_EVERY_MS) return
        runCatching { checked.parentFile?.mkdirs(); checked.writeText("") }
        Thread {
            val (version, url) = index() ?: return@Thread
            val current = installed?.parentFile?.parentFile?.name ?: return@Thread
            if (compareVersions(version, current) <= 0) return@Thread
            Log.i(TAG, "a newer browser: $current -> $version")
            fetch(version, url) {}
        }.apply { isDaemon = true; name = "chrome-update" }.start()
    }

    /** Every version but the newest: nothing uses them any more. */
    internal fun removeOlder() {
        val keep = installed?.parentFile?.parentFile ?: return
        for (v in versions()) if (v != keep) runCatching { v.deleteRecursively() }
    }

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
        runCatching { checked.parentFile?.mkdirs(); checked.writeText("") }
        fetch(version, url, onStatus)
    }

    /** Download and unpack [version] from [url]; its program, or null. */
    private fun fetch(version: String, url: String, onStatus: (String) -> Unit): File? {
        // Unpacked beside the others and moved into place whole: an update runs
        // while the browser may start, and must never be found half there.
        val target = File(home, "$version$PARTIAL").apply { deleteRecursively(); mkdirs() }
        val zip = File(target, "chrome.zip")
        try {
            onStatus("Getting a browser ready… 0%")
            if (!download(url, zip, onStatus)) {
                target.deleteRecursively()
                return null
            }
            onStatus("Unpacking the browser…")
            unzip(zip, target)
            zip.delete()
            if (!File(target, inside).isFile) {
                onStatus("The browser didn't unpack as expected.")
                target.deleteRecursively()
                return null
            }
            val final = File(home, version)
            final.deleteRecursively()
            if (!target.renameTo(final)) error("couldn't move the browser into place")
            onStatus("Browser ready.")
            Log.i(TAG, "fetched Chrome $version")
            return File(final, inside)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't fetch a browser: ${e.message}")
            onStatus("Couldn't fetch a browser: ${e.message}")
            target.deleteRecursively()
            return null
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
        var hashes: List<String> = emptyList()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body ?: return false
            if (!response.isSuccessful) return false
            hashes = response.headers("x-goog-hash")
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
        if (into.length() == 0L) return false
        if (!verify(into, hashes)) {
            Log.w(TAG, "the browser download doesn't match its checksum")
            onStatus("The browser download was damaged on the way. Try again.")
            into.delete()
            return false
        }
        return true
    }

    /**
     * Whether [file] matches the MD5 Google's storage sends with it
     * (`x-goog-hash: md5=<base64>`). Google publishes no SHA-256 for these
     * builds, so this proves the file arrived as sent, not who made it; the
     * index and the file both come over https from Google. No MD5 given: true.
     */
    internal fun verify(file: File, googHash: List<String>): Boolean {
        val expected = googHash.flatMap { it.split(',') }.map { it.trim() }
            .firstOrNull { it.startsWith("md5=") }?.removePrefix("md5=")
            ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
            ?: return true
        val md5 = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md5.update(buf, 0, n) }
        }
        return md5.digest().contentEquals(expected)
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
