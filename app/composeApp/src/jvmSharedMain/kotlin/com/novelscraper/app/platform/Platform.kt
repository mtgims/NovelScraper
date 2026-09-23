package com.novelscraper.app.platform

import androidx.compose.runtime.Composable
import java.io.File
import java.io.InputStream

// Everything the shared code needs from the platform it runs on. Each target
// provides the `actual`s (androidMain/.../platform/Platform.android.kt,
// desktopMain/.../platform/Platform.desktop.kt), so a missing piece is a compile
// error rather than a crash at runtime.

/**
 * A small persistent key/value store. One store per name; on Android each is the
 * SharedPreferences file of the same name, so settings written by earlier versions
 * (the login cookie, the server address, reader prefs) are read back unchanged.
 * Writes are asynchronous, like SharedPreferences.apply().
 */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?
    fun getStringSet(key: String): Set<String>?
    fun getFloat(key: String, default: Float): Float
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean

    fun putString(key: String, value: String)
    fun putStringSet(key: String, value: Set<String>)
    fun putFloat(key: String, value: Float)
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
}

expect fun settingsStore(name: String): KeyValueStore

/** An embedded browser is available (Android WebView): the NovelUpdates browser
 *  and rendering JS-only pages for the scrape relay. */
expect val hasWebView: Boolean

/** A line about how a browser check will be answered on this device (which
 *  browser opens, or that one has to be fetched first), or null if there is
 *  nothing worth saying. */
expect val browserCheckNote: String?

/** The platform has its own speech engine with installed voices (Android
 *  TextToSpeech). Without one, narration uses the downloadable Kokoro/Piper models. */
expect val hasSystemTts: Boolean

/** The User-Agent a normal browser on this kind of device sends; used for the
 *  requests made on the user's behalf (scrape relay, source extensions). */
expect val browserUserAgent: String

/** True in debug builds (gates request logging). */
expect val isDebugBuild: Boolean

/** Private, persistent app storage (downloaded TTS models live here). */
expect fun appFilesDir(): File

/** Private scratch space the system may clear. */
expect fun appCacheDir(): File

/**
 * Chapter HTML to plain text, one paragraph per line, with U+FFFC standing in for
 * each <img>. The reader's sentence highlighting and the narrator both split this
 * text, so it has to be the same function on both sides of a platform.
 */
expect fun htmlToPlain(html: String): String

/** Percent-encode a value for use inside a navigation route. */
expect fun encodeRouteArg(value: String): String

/** Open a web page in the user's browser. */
expect fun openInBrowser(url: String)

/** A short, non-blocking message to the user. */
expect fun showToast(message: String, long: Boolean = false)

expect object Log {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String, t: Throwable? = null)
}

// --- UI glue ------------------------------------------------------------------

/** Intercept the system back gesture / button while [enabled]. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** Show or hide the system status/navigation bars (immersive reading). The bars
 *  come back when the caller leaves composition. */
@Composable
expect fun SystemBarsVisible(visible: Boolean)

/** A file the user picked, read lazily. */
class PickedFile(val name: String, val open: () -> InputStream?)

/** Returns a launcher that lets the user pick one or more EPUB files. */
@Composable
expect fun rememberEpubPicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit

/** Where a "save as" landed. [write] returns false if the bytes couldn't be written. */
fun interface SaveTarget {
    fun write(bytes: ByteArray): Boolean
}

/**
 * Write a file into the device's Downloads, as the app's other downloads go
 * (Android: the Downloads collection, so it shows in Files; desktop: the
 * Downloads folder). [write] is called on a background thread with the file's
 * output; returns the name it landed under, or null if it couldn't be written.
 */
expect suspend fun saveToDownloads(
    fileName: String,
    mimeType: String,
    write: suspend (java.io.OutputStream) -> Unit,
): String?

/** Returns a launcher for a "save as" dialog, taking the suggested file name.
 *  [onTarget] gets null if the user cancels. */
@Composable
expect fun rememberFileSaver(mimeType: String, onTarget: (SaveTarget?) -> Unit): (String) -> Unit

/** Wraps [action] so it first asks for whatever permission background narration
 *  needs (Android 13+: notifications). [action] runs whether or not it is granted,
 *  as narration still works without the notification. */
@Composable
expect fun rememberNarrationPermission(action: () -> Unit): () -> Unit

/** A voice of the platform's own speech engine. */
data class SystemVoice(val name: String, val label: String)

/** The platform speech engine's installed voices, current language first. Empty
 *  until they have been enumerated. */
@Composable
expect fun rememberSystemVoices(): List<SystemVoice>

/** True if this device can show a browser to pass a site's check (Cloudflare). */
expect val canPassSiteChecks: Boolean

/**
 * Open a real browser at [url] and wait until the site's check is passed,
 * handing what it collected (cookies) to the extensions' cookie jar. True if the
 * site let us through. [onStatus] reports what is happening, for the UI.
 */
expect suspend fun passSiteCheck(url: String, onStatus: (String) -> Unit = {}): Boolean

/** True if this device can load a page in a real browser (see [fetchThroughBrowser]). */
expect val canFetchThroughBrowser: Boolean

/**
 * Load [url] in a real browser and hand back the page's HTML, for sites that
 * refuse plain requests however good the cookies are (Cloudflare reads more than
 * cookies). Null if the page couldn't be loaded. GET only.
 */
expect suspend fun fetchThroughBrowser(url: String): String?

/**
 * True if this page is a site's browser check rather than its content.
 * Cloudflare puts its challenge script on ordinary pages too, so that alone
 * means nothing: the interstitial names itself in the title and carries the
 * challenge's own options.
 */
fun looksLikeBrowserCheck(html: String): Boolean {
    val head = html.take(6000)
    return head.contains("Just a moment", ignoreCase = true) ||
        head.contains("cf_chl_opt", ignoreCase = true) ||
        head.contains("cf-chl-bypass", ignoreCase = true) ||
        head.contains("Checking your browser before accessing", ignoreCase = true) ||
        // DDoS-Guard (Ranobes) holds pages back the same way.
        head.contains("ddos-guard", ignoreCase = true) ||
        head.contains("check_are_you_bot", ignoreCase = true)
}
