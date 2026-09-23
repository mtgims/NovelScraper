package com.novelscraper.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import java.awt.FileDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

// Desktop (Linux; Windows later) platform. See DesktopDirs for where data lives.

private val stores = ConcurrentHashMap<String, PropertiesStore>()

actual fun settingsStore(name: String): KeyValueStore =
    stores.getOrPut(name) { PropertiesStore(File(DesktopDirs.config, "$name.properties")) }

actual val hasWebView: Boolean = false

actual val browserCheckNote: String?
    get() = SystemBrowser.binary?.let { "It opens in ${it.name}, a browser already on this computer." }
        ?: "This computer has no browser the app can drive, so a current Chrome is fetched " +
        "for it the first time (about 190 MB)."

// What the app's own requests claim. A clearance cookie is tied to the browser
// that earned it, so this follows the browser actually being driven: the one on
// this computer once it has said its name, remembered for the next run, and the
// bundled Chromium's until then.
private const val BUNDLED_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

actual val browserUserAgent: String
    get() = SystemBrowser.userAgent
        ?: settingsStore("site-checks").getString("user-agent", null)
        ?: BUNDLED_UA

actual val hasSystemTts: Boolean = false

actual val isDebugBuild: Boolean = System.getProperty("novelscraper.debug") == "true"

actual fun appFilesDir(): File = DesktopDirs.data

actual fun appCacheDir(): File = DesktopDirs.cache

actual fun htmlToPlain(html: String): String = HtmlPlainText.convert(html)

actual fun encodeRouteArg(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

actual fun openInBrowser(url: String) {
    val opened = runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (!desktop.isSupported(java.awt.Desktop.Action.BROWSE)) error("unsupported")
        desktop.browse(java.net.URI(url))
    }.isSuccess || runCatching { ProcessBuilder("xdg-open", url).start() }.isSuccess
    if (!opened) showToast("Couldn't open a browser for $url")
}

actual fun showToast(message: String, long: Boolean) {
    Toasts.queue.trySend(message to long)
}

actual object Log {
    actual fun d(tag: String, msg: String) { if (isDebugBuild) out("D/$tag: $msg") }
    actual fun i(tag: String, msg: String) = out("I/$tag: $msg")
    actual fun w(tag: String, msg: String) = out("W/$tag: $msg", error = true)
    actual fun e(tag: String, msg: String, t: Throwable?) {
        out("E/$tag: $msg", error = true)
        t?.printStackTrace()
    }

    // Started from a desktop launcher there is nowhere for a printed line to go,
    // and the one line that says why a browser check failed is worth having
    // afterwards. Kept to the last megabyte; nothing here is private beyond the
    // addresses the app was asked to fetch.
    private val file: java.io.File? by lazy {
        runCatching {
            java.io.File(appCacheDir(), "novelscraper.log").also { log ->
                log.parentFile?.mkdirs()
                if (log.length() > 1_000_000L) log.writeText("")
            }
        }.getOrNull()
    }

    private val stamp = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    @Synchronized
    private fun out(line: String, error: Boolean = false) {
        if (error) System.err.println(line) else println(line)
        runCatching { file?.appendText("${java.time.LocalTime.now().format(stamp)} $line\n") }
    }
}

// --- UI glue ------------------------------------------------------------------

// Compose routes Esc (and the window's back gesture) through the same dispatcher
// the navigation host uses, so a handler here takes priority over "pop the screen".
// BackHandler is deprecated in favour of NavigationEventHandler, which lives in a
// newer navigationevent line than the one Compose 1.10.3 ships with; revisit when
// Compose moves.
@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) =
    BackHandler(enabled = enabled, onBack = onBack)

/** No system bars on a desktop window. */
@Composable
actual fun SystemBarsVisible(visible: Boolean) {}

@Composable
actual fun rememberEpubPicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit {
    val window = LocalAppWindow.current
    val current by rememberUpdatedState(onPicked)
    return {
        val dialog = FileDialog(window, "Choose EPUB files", FileDialog.LOAD).apply {
            directory = DesktopDirs.downloads.path  // where EPUBs usually are
            isMultipleMode = true
            setFilenameFilter { _, name -> name.endsWith(".epub", ignoreCase = true) }
            file = "*.epub"  // the filter Windows' dialog uses; GTK uses the one above
        }
        dialog.isVisible = true  // modal: returns once the user picks or cancels
        val files = dialog.files.orEmpty().filter { it.isFile }
        if (files.isNotEmpty()) current(files.map { f -> PickedFile(f.name) { f.inputStream() } })
    }
}

@Composable
actual fun rememberFileSaver(mimeType: String, onTarget: (SaveTarget?) -> Unit): (String) -> Unit {
    val window = LocalAppWindow.current
    val current by rememberUpdatedState(onTarget)
    return { suggested ->
        val dialog = FileDialog(window, "Save as", FileDialog.SAVE).apply {
            directory = DesktopDirs.downloads.path
            file = suggested
        }
        dialog.isVisible = true
        val name = dialog.file
        current(
            if (name == null) null
            else {
                val target = File(dialog.directory, name)
                SaveTarget { bytes -> runCatching { target.writeBytes(bytes) }.isSuccess }
            },
        )
    }
}

/** Nothing to ask for on desktop. */
@Composable
actual fun rememberNarrationPermission(action: () -> Unit): () -> Unit {
    val current by rememberUpdatedState(action)
    return { current() }
}

/** No platform speech engine on desktop (see [hasSystemTts]). */
@Composable
actual fun rememberSystemVoices(): List<SystemVoice> = emptyList()

/** Desktop: the user's Downloads folder, with a unique name. */
actual suspend fun saveToDownloads(
    fileName: String,
    mimeType: String,
    write: suspend (java.io.OutputStream) -> Unit,
): String? = withContext(Dispatchers.IO) {
    val dir = DesktopDirs.downloads.apply { mkdirs() }
    val file = com.novelscraper.app.net.Downloads.reserve(dir, fileName)
    file.outputStream().buffered().use { write(it) }
    file.name
}
