package com.novelscraper.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import java.awt.FileDialog
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

// Desktop (Linux; Windows later) platform. See DesktopDirs for where data lives.

private val stores = ConcurrentHashMap<String, PropertiesStore>()

actual fun settingsStore(name: String): KeyValueStore =
    stores.getOrPut(name) { PropertiesStore(File(DesktopDirs.config, "$name.properties")) }

actual val hasWebView: Boolean = false

actual val browserUserAgent: String =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"

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
    actual fun d(tag: String, msg: String) { if (isDebugBuild) println("D/$tag: $msg") }
    actual fun i(tag: String, msg: String) = println("I/$tag: $msg")
    actual fun w(tag: String, msg: String) = System.err.println("W/$tag: $msg")
    actual fun e(tag: String, msg: String, t: Throwable?) {
        System.err.println("E/$tag: $msg")
        t?.printStackTrace()
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
