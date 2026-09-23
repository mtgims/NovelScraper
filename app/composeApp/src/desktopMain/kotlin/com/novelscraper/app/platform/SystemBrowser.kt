package com.novelscraper.app.platform

import com.novelscraper.app.extensions.BrowserCookieJar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The browser already on this computer, driven over its debugging protocol.
 *
 * The app carries a Chromium of its own (see [ensureSiteCheckBrowser]), but the
 * newest build available to it is two years old, and a browser check counts an
 * old browser against you. The one the reader actually uses is current, has the
 * graphics card behind it, and is the browser this machine is known for, so
 * checks that refuse the bundled one pass in it.
 *
 * It runs in a profile of the app's own under the app's data folder, so nothing
 * touches the reader's real browsing, and the window sits off-screen until a
 * check needs a person, exactly as the bundled one does.
 */
object SystemBrowser {

    private const val TAG = "SystemBrowser"
    private val json = Json { ignoreUnknownKeys = true }

    /** Chromium-family browsers, best first. Firefox can't be driven this way. */
    private val CANDIDATES = listOf(
        "chromium", "chromium-browser", "google-chrome-stable", "google-chrome",
        "brave", "brave-browser", "microsoft-edge", "microsoft-edge-stable", "vivaldi-stable", "vivaldi",
    )

    /** The browser to drive: whatever `NOVELSCRAPER_BROWSER` names, else the
     *  first one on the PATH. Worked out once. */
    val binary: File? by lazy { find() }

    val available: Boolean get() = binary != null

    /** What the browser calls itself, once it has told us (the app's own requests
     *  claim the same, so a clearance cookie stays with the name that earned it). */
    @Volatile
    var userAgent: String? = null
        private set

    private var process: Process? = null
    private var socket: WebSocket? = null
    private var browserSession: String? = null
    private var pageSession: String? = null
    private var targetId: String? = null
    private var windowId: Int? = null

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val nextId = AtomicInteger(1)

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // the debugging socket stays open
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    // --- what the rest of the app uses -------------------------------------------

    /**
     * Loads [url] and hands back the page the browser ends up with, or null if it
     * couldn't be driven at all (so the caller can fall back to the bundled one).
     *
     * The window stays off-screen while the site behaves. If what comes back is
     * still a check after [patienceMs], it is brought on screen so the reader can
     * answer it, and [onShown] is told; the clock then becomes theirs.
     */
    suspend fun load(
        url: String,
        patienceMs: Long,
        interactiveMs: Long,
        loadMs: Long,
        onShown: () -> Unit = {},
    ): String? {
        if (!start()) return null
        val started = System.currentTimeMillis()
        var deadline = started + loadMs
        var shown = false
        try {
            call("Page.navigate", buildJsonObject { put("url", url) }, pageSession) ?: return null
            while (System.currentTimeMillis() < deadline) {
                if (ready()) break
                delay(250)
            }
            var page = html()
            while (page != null && looksLikeBrowserCheck(page) && System.currentTimeMillis() < deadline) {
                if (!shown && System.currentTimeMillis() - started > patienceMs) {
                    shown = true
                    Log.i(TAG, "the check needs a visible browser; showing it")
                    onShown()
                    show()
                    deadline = System.currentTimeMillis() + interactiveMs
                }
                delay(1_000)
                page = html()
            }
            return page?.takeIf { !looksLikeBrowserCheck(it) }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't drive the browser: ${e.message}")
            stop()
            return null
        } finally {
            if (shown) runCatching { hide() }
        }
    }

    /** The cookies the browser holds for [url] (what a passed check left behind). */
    suspend fun cookies(url: String): List<BrowserCookieJar.BrowserCookie> {
        val result = call(
            "Network.getCookies",
            buildJsonObject { putJsonArray("urls") { add(url) } },
            pageSession,
        ) ?: return emptyList()
        val list = result["cookies"]?.jsonArray ?: return emptyList()
        return list.mapNotNull { entry ->
            val c = entry.jsonObject
            val name = c["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val expires = c["expires"]?.jsonPrimitive?.double ?: -1.0
            BrowserCookieJar.BrowserCookie(
                name = name,
                value = c["value"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                domain = c["domain"]?.jsonPrimitive?.contentOrNull,
                path = c["path"]?.jsonPrimitive?.contentOrNull,
                // A session cookie has no date of its own; the jar gives it one.
                expiresAt = if (expires > 0) (expires * 1000).toLong() else 0L,
                secure = c["secure"]?.jsonPrimitive?.boolean ?: false,
                httpOnly = c["httpOnly"]?.jsonPrimitive?.boolean ?: false,
            )
        }
    }

    /** Bring the window on screen, for a check that wants a person. */
    suspend fun show() {
        val id = windowId ?: return
        call(
            "Browser.setWindowBounds",
            buildJsonObject {
                put("windowId", id)
                putJsonObject("bounds") {
                    put("left", 120); put("top", 90); put("width", 1100); put("height", 860)
                    put("windowState", "normal")
                }
            },
            browserSession,
        )
        call("Page.bringToFront", buildJsonObject {}, pageSession)
    }

    /** Park it back off-screen. */
    suspend fun hide() {
        val id = windowId ?: return
        call(
            "Browser.setWindowBounds",
            buildJsonObject {
                put("windowId", id)
                putJsonObject("bounds") { put("left", OFFSCREEN); put("top", OFFSCREEN) }
            },
            browserSession,
        )
    }

    /** What the tab is showing right now, whatever state it is in. */
    internal suspend fun currentPage(): String? = html()

    /** Close the browser when the app closes. */
    fun dispose() = stop()

    // --- starting and stopping -----------------------------------------------------

    private fun find(): File? {
        // Under test, only the test that asks for a browser gets one.
        if (System.getProperty("novelscraper.tests") == "true" &&
            System.getProperty("live.browser").isNullOrBlank()
        ) return null
        System.getenv("NOVELSCRAPER_BROWSER")?.takeIf { it.isNotBlank() }?.let { named ->
            val file = File(named)
            if (file.canExecute()) return file
            return onPath(named)
        }
        return CANDIDATES.firstNotNullOfOrNull { onPath(it) }
    }

    private fun onPath(name: String): File? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }

    /** Start the browser and attach to a tab, or say it couldn't be done. */
    @Synchronized
    private fun startProcess(): File? {
        val bin = binary ?: return null
        if (process?.isAlive == true) return bin
        val profile = File(appFilesDir(), "browser-profile").apply { mkdirs() }
        File(profile, "DevToolsActivePort").delete()
        // Under Wayland a window cannot ask to be placed, so it would open in the
        // middle of the screen on every check. Through XWayland it can be parked
        // off to the side, which is where a browser doing the app's errands
        // belongs, and the graphics card is still behind it either way.
        val wayland = !System.getenv("WAYLAND_DISPLAY").isNullOrBlank() ||
            System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true)
        val command = listOfNotNull(
            bin.path,
            if (wayland) "--ozone-platform=x11" else null,
            "--user-data-dir=${profile.absolutePath}",
            "--remote-debugging-port=0",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-session-crashed-bubble",
            "--hide-crash-restore-bubble",
            // The window is parked off-screen, and a parked window must keep
            // running at full speed: a check that is throttled never finishes.
            "--window-position=$OFFSCREEN,$OFFSCREEN",
            "--window-size=1100,860",
            "--disable-background-timer-throttling",
            "--disable-backgrounding-occluded-windows",
            "--disable-renderer-backgrounding",
            "about:blank",
        )
        process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        return bin
    }

    /** Everything needed before a page can be loaded: process, socket, tab. */
    private suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (socket != null && process?.isAlive == true && pageSession != null) return@withContext true
        stop()
        if (startProcess() == null) return@withContext false
        val profile = File(appFilesDir(), "browser-profile")
        val endpoint = withTimeoutOrNull(20_000) { awaitEndpoint(File(profile, "DevToolsActivePort")) }
        if (endpoint == null) {
            Log.w(TAG, "the browser didn't open its debugging port")
            stop()
            return@withContext false
        }
        if (!connect(endpoint)) { stop(); return@withContext false }
        val version = call("Browser.getVersion", buildJsonObject {}, null)
        userAgent = version?.get("userAgent")?.jsonPrimitive?.contentOrNull
        // Remembered, so the app's own requests claim the right browser from the
        // first request of the next run, before this one has been started.
        userAgent?.let { settingsStore("site-checks").putString("user-agent", it) }
        Log.i(TAG, "driving ${binary?.name}: ${version?.get("product")?.jsonPrimitive?.contentOrNull}")
        openTab()
    }

    /** The browser writes its port, then the path to its own socket, into the
     *  profile as soon as it is listening. */
    private suspend fun awaitEndpoint(file: File): String? {
        while (true) {
            if (file.isFile) {
                val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
                if (lines.size >= 2 && lines[0].isNotBlank()) return "ws://127.0.0.1:${lines[0]}${lines[1]}"
            }
            if (process?.isAlive != true) return null
            delay(150)
        }
    }

    private suspend fun connect(endpoint: String): Boolean {
        val open = CompletableDeferred<Boolean>()
        socket = http.newWebSocket(
            Request.Builder().url(endpoint).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { open.complete(true) }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    val id = message["id"]?.jsonPrimitive?.int ?: return
                    val result = message["result"]?.jsonObject
                        ?: buildJsonObject { put("error", message["error"]?.toString() ?: "failed") }
                    pending.remove(id)?.complete(result)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    open.complete(false)
                    pending.values.forEach { it.complete(buildJsonObject { put("error", t.message ?: "closed") }) }
                    pending.clear()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    pending.values.forEach { it.complete(buildJsonObject { put("error", "closed") }) }
                    pending.clear()
                }
            },
        )
        return withTimeoutOrNull(10_000) { open.await() } == true
    }

    /** One tab, kept for the whole run, so a site that has been let through stays
     *  let through. */
    private suspend fun openTab(): Boolean {
        // The window the browser opened at startup, which is the one the position
        // and size on the command line applied to. A second tab beside it would
        // only be something for the reader to wonder about.
        val existing = call("Target.getTargets", buildJsonObject {}, null)
            ?.get("targetInfos")?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "page" }
            ?.jsonObject?.get("targetId")?.jsonPrimitive?.contentOrNull
        targetId = existing
            ?: call("Target.createTarget", buildJsonObject { put("url", "about:blank") }, null)
                ?.get("targetId")?.jsonPrimitive?.contentOrNull
            ?: return false
        val attached = call(
            "Target.attachToTarget",
            buildJsonObject { put("targetId", targetId); put("flatten", true) },
            null,
        ) ?: return false
        pageSession = attached["sessionId"]?.jsonPrimitive?.contentOrNull ?: return false
        call("Page.enable", buildJsonObject {}, pageSession)
        call("Network.enable", buildJsonObject {}, pageSession)
        windowId = call(
            "Browser.getWindowForTarget",
            buildJsonObject { put("targetId", targetId) },
            null,
        )?.get("windowId")?.jsonPrimitive?.int
        hide()
        return true
    }

    @Synchronized
    private fun stop() {
        runCatching { socket?.close(1000, null) }
        runCatching { process?.destroy() }
        socket = null
        process = null
        pageSession = null
        browserSession = null
        targetId = null
        windowId = null
        pending.clear()
    }

    // --- the protocol itself ---------------------------------------------------------

    /** One call and its answer; null if the browser is gone or took too long. */
    private suspend fun call(method: String, params: JsonObject, session: String?): JsonObject? {
        val ws = socket ?: return null
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        val message = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
            if (session != null) put("sessionId", session)
        }
        if (!ws.send(message.toString())) {
            pending.remove(id)
            return null
        }
        val result = withTimeoutOrNull(30_000) { answer.await() }
        pending.remove(id)
        if (result == null || result.containsKey("error")) {
            if (result != null) Log.w(TAG, "$method: ${result["error"]}")
            return null
        }
        return result
    }

    /** True once the page has finished loading. */
    private suspend fun ready(): Boolean = evaluate("document.readyState") == "complete"

    private suspend fun html(): String? = evaluate("document.documentElement.outerHTML")

    private suspend fun evaluate(expression: String): String? {
        val result = call(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", expression)
                put("returnByValue", true)
                put("awaitPromise", false)
            },
            pageSession,
        ) ?: return null
        return result["result"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
    }

    private const val OFFSCREEN = -2400
}
