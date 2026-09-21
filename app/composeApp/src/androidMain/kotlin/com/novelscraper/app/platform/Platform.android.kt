package com.novelscraper.app.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.novelscraper.app.BuildConfig
import java.io.File
import java.util.Locale

/** The application context, set first thing in App.onCreate. */
lateinit var appContext: Context
    private set

fun initPlatform(context: Context) {
    appContext = context.applicationContext
}

private class PrefsStore(private val p: SharedPreferences) : KeyValueStore {
    override fun getString(key: String, default: String?) = p.getString(key, default)
    override fun getStringSet(key: String): Set<String>? = p.getStringSet(key, null)
    override fun getFloat(key: String, default: Float) = p.getFloat(key, default)
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun getBoolean(key: String, default: Boolean) = p.getBoolean(key, default)

    override fun putString(key: String, value: String) = p.edit().putString(key, value).apply()
    override fun putStringSet(key: String, value: Set<String>) = p.edit().putStringSet(key, value).apply()
    override fun putFloat(key: String, value: Float) = p.edit().putFloat(key, value).apply()
    override fun putInt(key: String, value: Int) = p.edit().putInt(key, value).apply()
    override fun putBoolean(key: String, value: Boolean) = p.edit().putBoolean(key, value).apply()
    override fun remove(key: String) = p.edit().remove(key).apply()
}

actual fun settingsStore(name: String): KeyValueStore =
    PrefsStore(appContext.getSharedPreferences(name, Context.MODE_PRIVATE))

actual val hasWebView: Boolean = true

// A believable mobile Chrome, so requests from a phone's IP look like a phone.
actual val browserUserAgent: String =
    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"

actual val hasSystemTts: Boolean = true

actual val isDebugBuild: Boolean = BuildConfig.DEBUG

actual fun appFilesDir(): File = appContext.filesDir

actual fun appCacheDir(): File = appContext.cacheDir

actual fun htmlToPlain(html: String): String =
    HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()

actual fun encodeRouteArg(value: String): String = Uri.encode(value)

actual fun openInBrowser(url: String) {
    runCatching {
        appContext.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { showToast("No browser to open $url") }
}

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

actual fun showToast(message: String, long: Boolean) {
    val show = {
        Toast.makeText(appContext, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
    if (Looper.myLooper() == Looper.getMainLooper()) show() else mainHandler.post(show)
}

actual object Log {
    actual fun d(tag: String, msg: String) { android.util.Log.d(tag, msg) }
    actual fun i(tag: String, msg: String) { android.util.Log.i(tag, msg) }
    actual fun w(tag: String, msg: String) { android.util.Log.w(tag, msg) }
    actual fun e(tag: String, msg: String, t: Throwable?) { android.util.Log.e(tag, msg, t) }
}

// --- UI glue ------------------------------------------------------------------

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) =
    BackHandler(enabled = enabled, onBack = onBack)

private fun findActivity(context: Context): Activity? {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
actual fun SystemBarsVisible(visible: Boolean) {
    // Edge-to-edge is on; a swipe still reveals the bars transiently while hidden.
    // Always restore them on exit so the rest of the app isn't left full-screen.
    val view = LocalView.current
    LaunchedEffect(visible) {
        val window = findActivity(view.context)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(view) {
        onDispose {
            findActivity(view.context)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
actual fun rememberEpubPicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val cr = ctx.contentResolver
            onPicked(uris.map { uri ->
                PickedFile(epubName(displayName(ctx, uri))) { cr.openInputStream(uri) }
            })
        }
    }
    return { launcher.launch("application/epub+zip") }
}

private fun displayName(ctx: Context, uri: Uri): String? =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

/** The upload name for a picked EPUB. Some providers report a title rather than
 *  the file name (a DownloadManager download shows as "Book · volume 1"), and the
 *  server only accepts names ending in .epub; the picker is limited to EPUBs, so
 *  the extension is safe to add. */
internal fun epubName(displayName: String?): String {
    val name = displayName?.trim().orEmpty().ifEmpty { "book" }
    return if (name.endsWith(".epub", ignoreCase = true)) name else "$name.epub"
}

@Composable
actual fun rememberFileSaver(mimeType: String, onTarget: (SaveTarget?) -> Unit): (String) -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeType)
    ) { uri ->
        onTarget(uri?.let { u ->
            SaveTarget { bytes ->
                runCatching { ctx.contentResolver.openOutputStream(u)?.use { it.write(bytes) } }.isSuccess
            }
        })
    }
    return { name -> launcher.launch(name) }
}

@Composable
actual fun rememberNarrationPermission(action: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val current by rememberUpdatedState(action)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { current() }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else current()
    }
}

@Composable
actual fun rememberSystemVoices(): List<SystemVoice> {
    val ctx = LocalContext.current
    var voices by remember { mutableStateOf<List<SystemVoice>>(emptyList()) }
    // Enumerate the device's voices via a short-lived engine.
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val cur = Locale.getDefault().language
                voices = runCatching {
                    engine!!.voices
                        // Every installed voice, all languages; skip only ones the
                        // engine reports as not-installed. Current language first.
                        ?.filter { it.name != null && it.features?.contains("notInstalled") != true }
                        ?.sortedWith(
                            compareBy(
                                { it.locale.language != cur },
                                { it.locale.displayName },
                                { it.name },
                            ),
                        )
                        ?.map { SystemVoice(it.name, "${it.locale.displayName} · ${it.name.substringAfterLast('-')}") }
                        ?: emptyList()
                }.getOrDefault(emptyList())
            }
        }
        onDispose { engine.shutdown() }
    }
    return voices
}
