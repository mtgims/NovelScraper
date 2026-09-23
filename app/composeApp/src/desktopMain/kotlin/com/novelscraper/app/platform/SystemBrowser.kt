package com.novelscraper.app.platform

import com.novelscraper.app.extensions.BrowserCookieJar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
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
     *  first one on the PATH, else one the app fetched for itself. */
    val binary: File?
        get() = chosen ?: locate()?.also { chosen = it }

    val available: Boolean get() = binary != null

    @Volatile private var chosen: File? = null
    @Volatile private var noSandbox = false

    /** True when the browser at the other end was already running: someone else's
     *  windows are in it, and it is not ours to close. */
    @Volatile private var adopted = false

    /** `NOVELSCRAPER_BROWSER_VISIBLE=1` keeps the window on screen the whole
     *  time, for a desktop where a check won't finish out of sight. */
    private val keepVisible: Boolean =
        System.getenv("NOVELSCRAPER_BROWSER_VISIBLE").orEmpty().let { it == "1" || it.equals("true", true) }

    private fun locate(): File? {
        // Under test, only the tests that ask for a browser get one: the live
        // browser check, and the live plugin runs against real sites.
        if (System.getProperty("novelscraper.tests") == "true" &&
            System.getProperty("live.browser").isNullOrBlank() &&
            System.getProperty("live.plugins").isNullOrBlank()
        ) return null
        return find() ?: ChromeDownload.installed
    }

    /**
     * Makes sure there is a browser to drive, fetching a current Chrome if this
     * machine has none of its own. [onStatus] carries the wait to the UI.
     */
    suspend fun ensure(onStatus: (String) -> Unit): Boolean {
        if (available) return true
        if (System.getProperty("novelscraper.tests") == "true") return false
        val fetched = ChromeDownload.ensure(onStatus) ?: return false
        chosen = fetched
        return true
    }

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

    @Volatile private var navigatedAt = 0L
    @Volatile private var loadedAt = 0L
    @Volatile private var idleAt = 0L

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
        // Once a page of the site is open, its other pages can be asked for from
        // inside it, the way the site's own scripts ask: an answer in a moment,
        // rather than a whole page loaded, drawn and waited on. Only an answer
        // that comes back as a check goes the long way round.
        fromInsideThePage(url)?.let { return it }
        // Not on this site yet: go to its front page, which is where a check is
        // met and answered, and ask it for the page wanted afterwards. One page
        // is loaded the slow way per site per session, not one per request.
        val origin = originOf(url)
        val landing = if (origin != null && currentOrigin() != origin) origin else url
        val started = System.currentTimeMillis()
        var deadline = started + loadMs
        var shown = false
        try {
            navigate(landing) ?: return null
            var page = settled(deadline)
            var retries = 0
            var lastRetry = System.currentTimeMillis()
            while (page != null && looksLikeBrowserCheck(page) && System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (!shown && now - started > patienceMs) {
                    shown = true
                    Log.i(TAG, "the check needs a visible browser; showing it")
                    onShown()
                    show()
                    // A fresh go at the check in a window that is on screen and
                    // being drawn. Some checks never finish in one that isn't,
                    // and sit there restarting themselves instead.
                    navigate(landing)
                    page = settled(now + 20_000)
                    deadline = System.currentTimeMillis() + interactiveMs
                    continue
                }
                // While it is still ours to deal with, ask again every so often:
                // a check that has quietly issued its cookie usually only needs
                // the page fetched once more to be let through. Never once the
                // window is on screen, where a reload lands under the reader's
                // own hands.
                if (!shown && retries < MAX_RETRIES && now - lastRetry > RETRY_MS) {
                    retries++
                    lastRetry = now
                    Log.i(TAG, "still a check; asking again (${retries}/$MAX_RETRIES)")
                    navigate(landing)
                    page = settled(now + 20_000)
                    continue
                }
                delay(1_000)
                page = html()
            }
            val landed = page?.takeIf { !looksLikeBrowserCheck(it) } ?: return null
            // The front page was only the way in; the page actually wanted is
            // asked for from inside it.
            return if (landing == url) landed else fromInsideThePage(url) ?: navigateTo(url, deadline)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't drive the browser: ${e.message}")
            stop()
            return null
        } finally {
            if (shown) runCatching { hide() }
        }
    }

    private suspend fun navigate(url: String): JsonObject? {
        navigatedAt = System.currentTimeMillis()
        return call("Page.navigate", buildJsonObject { put("url", url) }, pageSession)
    }

    /**
     * Waits for the page to finish loading (or [deadline]), then reads it once it
     * has stopped fetching things. A page's load event is not the end of it:
     * sites that fill themselves in afterwards hand back a document with nothing
     * in it yet, which a plugin reads as a source with no novels on it. If a page
     * never goes quiet, its load event will do.
     */
    private suspend fun settled(deadline: Long): String? {
        while (System.currentTimeMillis() < deadline) {
            if (idleAt >= navigatedAt) break
            if (loadedAt >= navigatedAt && System.currentTimeMillis() - loadedAt > SETTLE_AFTER_LOAD_MS) break
            delay(200)
        }
        // Quiet is not the same as finished: a site can sit on a bare shell for a
        // second or two and then fill it in, or replace the document outright.
        // The page is taken once it has held still.
        var page = html()
        var changed = System.currentTimeMillis()
        val until = minOf(deadline, System.currentTimeMillis() + SETTLE_MAX_MS)
        while (System.currentTimeMillis() < until) {
            delay(500)
            val next = html() ?: continue
            if (next.length != page?.length) {
                page = next
                changed = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - changed > HELD_STILL_MS) {
                break
            }
        }
        return page
    }

    /** Load a page in the window itself, the slow way, when nothing else works. */
    private suspend fun navigateTo(url: String, deadline: Long): String? {
        navigate(url) ?: return null
        return settled(maxOf(deadline, System.currentTimeMillis() + 30_000))
            ?.takeIf { !looksLikeBrowserCheck(it) }
    }

    /** A GET asked for from inside a page of the same site, when one is open.
     *  Null when there is no such page, or the site answered with a check. */
    private suspend fun fromInsideThePage(url: String): String? {
        val origin = originOf(url) ?: return null
        val here = currentOrigin() ?: return null
        // A site that answers on both novelhall.com and www.novelhall.com sends
        // the browser to one of them, and a plugin may name the other. Asking
        // from the page for the name the page itself is under keeps it a request
        // within the site rather than one across origins, which a browser stops.
        val target = when {
            here == origin -> url
            bareHost(here) == bareHost(origin) -> here + url.removePrefix(origin)
            else -> return null
        }
        // The same Accept a browser sends when it goes to a page: a site can tell
        // a page being read from a script fetching something, and some of them
        // answer the two differently.
        val asAPage = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        )
        val reply = runCatching { request(target, "GET", asAPage, null) }.getOrNull() ?: return null
        if (reply.status !in 200..299) return throughAFrame(target)
        if (looksLikeBrowserCheck(reply.body)) {
            Log.i(TAG, "asked from inside the page and got a check; trying a frame")
            return throughAFrame(target)
        }
        return reply.body.takeIf { it.isNotBlank() }
    }

    /**
     * The page fetched into a frame of the page that is already open.
     *
     * Some paths are refused to anything that isn't the browser going to a page:
     * a script asking for them is turned away even with a pass in hand. A frame
     * loading is the browser going to a page, and one from the same site can be
     * read straight out of the document, which still beats loading the whole
     * thing in the window and waiting for it to settle.
     */
    private suspend fun throughAFrame(url: String): String? {
        val origin = originOf(url) ?: return null
        if (currentOrigin() != origin) return null
        val script = """
            (async () => {
              const f = document.createElement('iframe');
              f.style.cssText = 'position:absolute;width:0;height:0;border:0;left:-9999px';
              const settled = new Promise(res => {
                f.onload = () => res();
                f.onerror = () => res();
                setTimeout(res, 20000);
              });
              f.src = ${q(url)};
              document.body.appendChild(f);
              await settled;
              let html = '';
              try { html = f.contentDocument.documentElement.outerHTML; } catch (e) { html = ''; }
              f.remove();
              return html;
            })()
        """.trimIndent()
        val html = evaluate(script, await = true)?.takeIf { it.length > 500 } ?: return null
        if (looksLikeBrowserCheck(html)) return null
        Log.i(TAG, "GET $url in a frame -> ${html.length} chars")
        return html
    }

    /** A host without the "www." a site may or may not put in front of it. */
    private fun bareHost(origin: String): String =
        origin.substringAfter("://").removePrefix("www.").lowercase()

    private fun originOf(url: String): String? =
        runCatching { java.net.URI(url) }.getOrNull()?.let { "${it.scheme}://${it.authority}" }

    private suspend fun currentOrigin(): String? = evaluate("location.origin")

    /**
     * Makes a request from inside the page, the way the site's own scripts do.
     *
     * A site that only answers a browser will not answer this app's own client
     * however good its cookies are, and a chapter list is usually fetched by the
     * site's script rather than sitting in the page. The tab is put on the site's
     * own address first, so the request goes out from there, with everything a
     * page of that site carries.
     */
    suspend fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: BrowserBody?,
    ): BrowserReply? {
        if (!start()) return null
        val origin = originOf(url) ?: return null
        if (currentOrigin() != origin) {
            navigate(origin)
            settled(System.currentTimeMillis() + 45_000)
        }
        // A form is built in the page, so the browser sets its own boundary and
        // the request looks like the one the site's own script would make.
        val makeBody = when (body) {
            null -> "undefined"
            is BrowserBody.Text -> q(body.value)
            is BrowserBody.Form -> buildString {
                append("(() => { const f = new FormData(); ")
                for ((name, value) in body.parts) append("f.append(${q(name)}, ${q(value)}); ")
                append("return f; })()")
            }
        }
        val script = """
            (async () => {
              const r = await fetch(${q(url)}, {
                method: ${q(method)},
                headers: ${Json.encodeToString(headers.filterKeys { !it.equals("content-type", true) })},
                body: $makeBody,
                credentials: 'include',
              });
              return r.status + '\u0000' + await r.text();
            })()
        """.trimIndent()
        val answer = evaluate(script, await = true) ?: return null
        val cut = answer.indexOf('\u0000')
        if (cut < 0) return null
        val status = answer.take(cut).toIntOrNull() ?: return null
        Log.i(TAG, "$method $url through the page -> $status, ${answer.length - cut - 1} chars")
        return BrowserReply(status, answer.substring(cut + 1))
    }

    private fun q(value: String): String = Json.encodeToString(value)

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
                putJsonObject("bounds") { put("windowState", "normal") }
            },
            browserSession,
        )
        call(
            "Browser.setWindowBounds",
            buildJsonObject {
                put("windowId", id)
                putJsonObject("bounds") {
                    put("left", 120); put("top", 90); put("width", 1100); put("height", 860)
                }
            },
            browserSession,
        )
        call("Page.bringToFront", buildJsonObject {}, pageSession)
    }

    /**
     * Put the window away again. It is minimised rather than moved out of sight:
     * where a window is placed is a request a desktop may refuse, and on a
     * Wayland one it always does, which left the browser sitting on screen for
     * the rest of the session. Minimising is honoured everywhere, and the page
     * inside keeps running, because the browser was started with the settings
     * that stop it being throttled when nobody is looking.
     */
    suspend fun hide() {
        if (keepVisible) return
        val id = windowId ?: return
        call(
            "Browser.setWindowBounds",
            buildJsonObject {
                put("windowId", id)
                putJsonObject("bounds") { put("windowState", "minimized") }
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
            // A fetched Chrome has no setuid helper, so on a kernel that won't
            // give it a namespace of its own it can only run without a sandbox.
            // Only ever after it has failed to start with one.
            if (noSandbox) "--no-sandbox" else null,
            "--no-default-browser-check",
            "--disable-session-crashed-bubble",
            "--hide-crash-restore-bubble",
            // The window is parked off-screen, and a parked window must keep
            // running at full speed: a check that is throttled never finishes.
            if (keepVisible) "--window-position=120,90" else "--window-position=$OFFSCREEN,$OFFSCREEN",
            "--window-size=1100,860",
            "--disable-background-timer-throttling",
            "--disable-backgrounding-occluded-windows",
            "--disable-renderer-backgrounding",
            // A window nobody can see is a window the compositor may decide is
            // covered, and a page Chromium then reports as hidden. A check does
            // not finish in a hidden page: it waits, gives up and starts again,
            // which is what a check that never ends looks like from outside.
            "--disable-features=CalculateNativeWinOcclusion",
            // Chromium tells every page it is being driven whenever the app is
            // attached to it, and a site's check refuses a browser that says so,
            // however ordinary the browsing behind it. This app is an embedded
            // browser with a reader sitting in front of it, answering the checks
            // by hand, which is what the phone's WebView already looks like: it
            // has no such flag to set. This puts the desktop on the same footing.
            "--disable-blink-features=AutomationControlled",
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
        if (socket != null && alive() && pageSession != null) return@withContext true
        stop()
        val profile = File(appFilesDir(), "browser-profile")
        val port = File(profile, "DevToolsActivePort")
        // A browser left behind by a run that ended badly still holds the profile,
        // and a second one on the same profile refuses to start. Rather than give
        // up and fall back to the browser we carry, take up with the one already
        // here: it is ours, in our own folder, and its cookies are the ones we
        // earned.
        if (port.isFile && adopt(endpointFrom(port))) {
            adopted = true
            return@withContext openTab()
        }
        adopted = false
        if (startProcess() == null) return@withContext false
        val endpoint = withTimeoutOrNull(20_000) { awaitEndpoint(port) }
        if (endpoint == null) {
            Log.w(TAG, "the browser didn't open its debugging port")
            stop()
            // Most often because one from a run that ended badly still holds the
            // profile and won't say where it is listening. It is ours, in our own
            // folder, so it can be shown the door.
            if (releaseProfileLock(profile)) {
                Log.i(TAG, "cleared a browser left behind on our profile; trying again")
                return@withContext start()
            }
            // A browser that died on the spot usually couldn't build its sandbox.
            if (!noSandbox) {
                noSandbox = true
                Log.i(TAG, "trying again without the sandbox")
                return@withContext start()
            }
            return@withContext false
        }
        if (!connect(endpoint)) { stop(); return@withContext false }
        val version = call("Browser.getVersion", buildJsonObject {}, null)
        userAgent = version?.get("userAgent")?.jsonPrimitive?.contentOrNull
        // Remembered, so the app's own requests claim the right browser from the
        // first request of the next run, before this one has been started.
        userAgent?.let { settingsStore("site-checks").putString("user-agent", it) }
        Log.i(TAG, "driving ${binary?.name}: ${version?.get("product")?.jsonPrimitive?.contentOrNull}")
        reportGraphics()
        openTab()
    }

    /**
     * Notes what the browser has to draw with. A check judges a browser largely
     * on its graphics: one falling back to software rendering, or with WebGL
     * switched off, looks like something that isn't a person's browser, so when
     * a check won't pass this is the first line worth reading.
     */
    private suspend fun reportGraphics() {
        val gpu = call("SystemInfo.getInfo", buildJsonObject {}, null)?.get("gpu")?.jsonObject ?: return
        val renderer = gpu["auxAttributes"]?.jsonObject?.get("glRenderer")?.jsonPrimitive?.contentOrNull
        val webgl = gpu["featureStatus"]?.jsonObject?.get("webgl")?.jsonPrimitive?.contentOrNull
        Log.i(TAG, "graphics: renderer=${renderer ?: "unknown"}, webgl=${webgl ?: "unknown"}")
    }

    /** The browser writes its port, then the path to its own socket, into the
     *  profile as soon as it is listening. */
    private suspend fun awaitEndpoint(file: File): String? {
        while (true) {
            endpointFrom(file)?.let { return it }
            if (process?.isAlive != true) return null
            delay(150)
        }
    }

    private fun endpointFrom(file: File): String? {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.size < 2 || lines[0].isBlank()) return null
        return "ws://127.0.0.1:${lines[0]}${lines[1]}"
    }

    /**
     * Ends a browser still holding [profile] from an earlier run, if there is
     * one. Chromium names the holder in SingletonLock, as host-pid; the process
     * is only ended once its own command line confirms it is sitting on this
     * folder, so nothing else on the machine is touched.
     */
    private fun releaseProfileLock(profile: File): Boolean {
        val lock = File(profile, "SingletonLock")
        val holder = runCatching { java.nio.file.Files.readSymbolicLink(lock.toPath()).toString() }.getOrNull()
        val pid = holder?.substringAfterLast('-')?.toLongOrNull() ?: return false
        val command = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull() ?: return false
        if (!command.contains(profile.absolutePath)) return false
        Log.i(TAG, "ending the browser left on our profile (pid $pid)")
        runCatching { ProcessHandle.of(pid).ifPresent { it.destroy() } }
        // It takes a moment to let go of the lock.
        Thread.sleep(1_500)
        runCatching { lock.delete() }
        runCatching { File(profile, "SingletonCookie").delete() }
        runCatching { File(profile, "SingletonSocket").delete() }
        return true
    }

    /** Connect to a browser that is already running on our profile. */
    private suspend fun adopt(endpoint: String?): Boolean {
        if (endpoint == null) return false
        if (!connect(endpoint)) return false
        val version = call("Browser.getVersion", buildJsonObject {}, null)
        if (version == null) {
            runCatching { socket?.close(1000, null) }
            socket = null
            return false
        }
        userAgent = version["userAgent"]?.jsonPrimitive?.contentOrNull
        userAgent?.let { settingsStore("site-checks").putString("user-agent", it) }
        Log.i(TAG, "took up with the browser already running on our profile")
        return true
    }

    /** True while there is a browser at the other end: one we started, or one we
     *  adopted and whose socket is still answering. */
    private fun alive(): Boolean = process?.isAlive == true || (process == null && socket != null)

    private suspend fun connect(endpoint: String): Boolean {
        val open = CompletableDeferred<Boolean>()
        socket = http.newWebSocket(
            Request.Builder().url(endpoint).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { open.complete(true) }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    val id = message["id"]?.jsonPrimitive?.int ?: run {
                        // Not an answer: the browser saying the page finished.
                        val event = message["method"]?.jsonPrimitive?.contentOrNull
                        if (event == "Page.loadEventFired") loadedAt = System.currentTimeMillis()
                        // The page has stopped fetching things: what is in the
                        // document now is what the reader would see.
                        if (event == "Page.lifecycleEvent" &&
                            message["params"]?.jsonObject?.get("name")
                                ?.jsonPrimitive?.contentOrNull == "networkIdle"
                        ) idleAt = System.currentTimeMillis()
                        return
                    }
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
        // A browser the app started has one window, opened where the command line
        // put it: that one is ours to use. A browser that was already running is
        // somebody's, with their tabs in it, so the app opens a window of its own
        // and leaves theirs alone rather than steering a page they are reading.
        val existing = if (adopted) null else call("Target.getTargets", buildJsonObject {}, null)
            ?.get("targetInfos")?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "page" }
            ?.jsonObject?.get("targetId")?.jsonPrimitive?.contentOrNull
        targetId = existing
            ?: call(
                "Target.createTarget",
                buildJsonObject { put("url", "about:blank"); if (adopted) put("newWindow", true) },
                null,
            )?.get("targetId")?.jsonPrimitive?.contentOrNull
            ?: return false
        val attached = call(
            "Target.attachToTarget",
            buildJsonObject { put("targetId", targetId); put("flatten", true) },
            null,
        ) ?: return false
        pageSession = attached["sessionId"]?.jsonPrimitive?.contentOrNull ?: return false
        call("Page.enable", buildJsonObject {}, pageSession)
        call("Page.setLifecycleEventsEnabled", buildJsonObject { put("enabled", true) }, pageSession)
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
        // A browser we adopted is left running, but the window we opened in it is
        // ours to clear away.
        if (adopted) targetId?.let { id ->
            runCatching {
                socket?.send(
                    buildJsonObject {
                        put("id", nextId.getAndIncrement())
                        put("method", "Target.closeTarget")
                        put("params", buildJsonObject { put("targetId", id) })
                    }.toString(),
                )
            }
        }
        runCatching { socket?.close(1000, null) }
        runCatching { process?.destroy() }
        socket = null
        process = null
        pageSession = null
        browserSession = null
        targetId = null
        windowId = null
        adopted = false
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

    /** True once the page loaded after the last request to go somewhere. */
    private fun ready(): Boolean = loadedAt >= navigatedAt

    /**
     * The page as the browser holds it, read out of its document rather than by
     * running script inside it. Asking a page to evaluate an expression is the
     * ordinary way to do this, and it is also the way an automated browser
     * announces itself: the machinery that carries the answer back is watched
     * for by the very checks this browser exists to get through. Reading the
     * document leaves that machinery alone.
     */
    /** Runs an expression in the page and hands back its value as text. */
    private suspend fun evaluate(expression: String, await: Boolean = false): String? {
        val result = call(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", expression)
                put("returnByValue", true)
                put("awaitPromise", await)
            },
            pageSession,
        ) ?: return null
        return result["result"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
    }

    private suspend fun html(): String? {
        val root = call(
            "DOM.getDocument",
            buildJsonObject { put("depth", 0) },
            pageSession,
        )?.get("root")?.jsonObject?.get("nodeId")?.jsonPrimitive?.int ?: return null
        return call(
            "DOM.getOuterHTML",
            buildJsonObject { put("nodeId", root) },
            pageSession,
        )?.get("outerHTML")?.jsonPrimitive?.contentOrNull
    }

    private const val OFFSCREEN = -2400

    /** How long a loaded page gets to go quiet before it is read anyway. */
    private const val SETTLE_AFTER_LOAD_MS = 2_500L

    /** How long a page must hold still to count as finished, and the longest
     *  it is given to get there. */
    private const val HELD_STILL_MS = 1_000L
    private const val SETTLE_MAX_MS = 5_000L

    /** How long a check gets before it is asked again, and how many times. */
    private const val RETRY_MS = 8_000L
    private const val MAX_RETRIES = 3
}
