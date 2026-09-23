package com.novelscraper.app.update

import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.appCacheDir
import com.novelscraper.app.platform.appVersion
import com.novelscraper.app.platform.installUpdate
import com.novelscraper.app.platform.updateAssetName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Keeping the app up to date without anyone going to fetch a file.
 *
 * Versions are published as releases, which is a list the app can read as well
 * as a person can: it asks what the newest one is, and if that is not the one
 * running, offers to fetch the build for this platform and put it in place. The
 * phone hands the downloaded package to the system installer, which asks the
 * reader before anything is replaced; the Linux app swaps the file it is
 * running from and restarts.
 */
object AppUpdates {

    private const val TAG = "Updates"
    private const val LATEST = "https://api.github.com/repos/mtgims/NovelScraper/releases/latest"
    const val RELEASES_PAGE = "https://github.com/mtgims/NovelScraper/releases"

    sealed interface State {
        /** Nothing has been asked yet, or the app is the newest there is. */
        data class Idle(val checkedAt: Long = 0, val upToDate: Boolean = false) : State
        data object Checking : State
        data class Available(val version: String, val notes: String, val url: String, val bytes: Long) : State
        data class Downloading(val version: String, val done: Long, val total: Long) : State
        /** Downloaded and waiting: the phone needs a tap, the desktop a restart. */
        data class Ready(val version: String, val file: File) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle())
    val state: StateFlow<State> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var http: OkHttpClient

    fun init(client: OkHttpClient) {
        http = client
    }

    /** What is running, as the release list spells it (no leading "v"). */
    val current: String get() = appVersion

    /**
     * Ask the releases page what the newest version is. [quietly] keeps a check
     * made on the app's own initiative from reporting a failure: a reader who
     * didn't ask doesn't need to hear that GitHub was unreachable.
     */
    fun check(quietly: Boolean = false) {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        scope.launch {
            if (!quietly) _state.value = State.Checking
            try {
                val release = withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build())
                        .execute().use { r ->
                            if (!r.isSuccessful) error("HTTP ${r.code}")
                            json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                        }
                }
                val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty().removePrefix("v")
                val notes = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val asset = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
                    .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == updateAssetName }
                when {
                    tag.isBlank() -> _state.value = State.Idle(now(), upToDate = false)
                    !isNewer(tag, current) -> _state.value = State.Idle(now(), upToDate = true)
                    asset == null -> {
                        Log.w(TAG, "release $tag has no $updateAssetName")
                        _state.value = State.Idle(now(), upToDate = false)
                    }
                    else -> _state.value = State.Available(
                        version = tag,
                        notes = notes.lineSequence().take(12).joinToString("\n").trim(),
                        url = asset["browser_download_url"]!!.jsonPrimitive.content,
                        bytes = asset["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "couldn't check for updates: ${e.message}")
                if (!quietly) _state.value = State.Failed("Couldn't reach the releases page.")
                else _state.value = State.Idle(now())
            }
        }
    }

    /** Fetch the build waiting in [State.Available]. */
    fun download() {
        val available = _state.value as? State.Available ?: return
        scope.launch {
            _state.value = State.Downloading(available.version, 0, available.bytes)
            try {
                val file = withContext(Dispatchers.IO) { fetch(available) }
                _state.value = State.Ready(available.version, file)
            } catch (e: Exception) {
                Log.w(TAG, "download failed: ${e.message}")
                _state.value = State.Failed("The download didn't finish.")
            }
        }
    }

    /** Put the downloaded build in place: the installer on a phone, the running
     *  file on the desktop. */
    fun install() {
        val ready = _state.value as? State.Ready ?: return
        scope.launch {
            val ok = runCatching { installUpdate(ready.file) }.getOrDefault(false)
            if (!ok) _state.value = State.Failed("Couldn't start the install.")
        }
    }

    fun dismiss() {
        _state.value = State.Idle(now())
    }

    private fun fetch(available: State.Available): File {
        val into = File(appCacheDir(), "updates").apply { mkdirs() }
        into.listFiles()?.forEach { it.delete() }
        val file = File(into, updateAssetName)
        http.newCall(Request.Builder().url(available.url).build()).execute().use { r ->
            val body = r.body ?: error("empty response")
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val total = if (body.contentLength() > 0) body.contentLength() else available.bytes
            var done = 0L
            body.byteStream().use { input ->
                file.outputStream().buffered().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        _state.value = State.Downloading(available.version, done, total)
                    }
                }
            }
        }
        return file
    }

    private fun now() = System.currentTimeMillis()

    /**
     * True if [candidate] is a later version than [running], compared piece by
     * piece so that 0.40.0 beats 0.9.9 and 0.39.10 beats 0.39.9, which string
     * order gets wrong both times.
     */
    internal fun isNewer(candidate: String, running: String): Boolean {
        fun parts(v: String) = v.trim().split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(running)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
