package com.novelscraper.app.platform

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.novelscraper.app.net.PrivateHosts
import com.novelscraper.app.extensions.BrowserCookieJar
import com.novelscraper.app.extensions.Extensions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Android has a WebView. */
actual val canFetchThroughBrowser: Boolean = true

private const val TAG = "BrowserFetch"
private val lock = Mutex()
/** The most a page may take to arrive. */
private const val LOAD_MS = 30_000L
/** How long a check that passes by itself gets to do so (DDoS-Guard's and
 *  Cloudflare's own take a few seconds), before the reader is asked instead. */
private const val CHECK_PATIENCE_MS = 15_000L
/** A page is read once it has held still this long: sites that fill
 *  themselves in by script hand back an empty shell before then. */
private const val HELD_STILL_MS = 700L
/** ...but not waited on longer than this once its document is there. */
private const val SETTLE_MAX_MS = 3_000L
/** The page is kept open this long after the last request, for the next one. */
private const val KEEP_MS = 2 * 60_000L

/**
 * One offscreen WebView, kept between requests the way a browser tab is.
 *
 * It used to be made afresh for every request: the site's page loaded whole,
 * then a flat 2.5 s wait, then thrown away. A source behind a guard asks for
 * dozens of pages to open one novel (Ranobes' chapter list is 25 chapters a
 * page), so a novel took a minute or more. Now the first page of a site is
 * loaded, and the site's other pages are asked for from inside it, the way its
 * own scripts ask: a request's time, not a page's.
 *
 * Touched on the main thread only.
 */
private object Kept {
    var view: WebView? = null
    var origin: String? = null
    var guard = PublicOnly(true)
    var startedAt = 0L
    var finishedAt = 0L
    var closer: Job? = null
}

@SuppressLint("SetJavaScriptEnabled")
private fun keptView(publicOnly: Boolean): WebView {
    Kept.view?.let { if (Kept.guard.on == publicOnly) return it else forget() }
    val guard = PublicOnly(publicOnly)
    val view = WebView(appContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = browserUserAgent
        // Pictures are nothing a source reads, and a page full of them is
        // slower to come in.
        settings.blockNetworkImage = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String, favicon: Bitmap?) { Kept.startedAt = System.currentTimeMillis() }
            override fun onPageFinished(v: WebView, u: String) { Kept.finishedAt = System.currentTimeMillis() }
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest) = guard.check(request)
        }
    }
    Kept.view = view
    Kept.guard = guard
    Kept.origin = null
    return view
}

/** Close the kept page (main thread). */
private fun forget() {
    Kept.view?.destroy()
    Kept.view = null
    Kept.origin = null
}

/** Close the kept page once nothing has used it for [KEEP_MS]. */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
private fun closeLater() {
    Kept.closer?.cancel()
    Kept.closer = GlobalScope.launch(Dispatchers.Main) {
        delay(KEEP_MS)
        lock.withLock { forget() }
    }
}

private fun originOf(url: String): String? =
    runCatching { java.net.URI(url) }.getOrNull()?.let { "${it.scheme}://${it.authority}" }

/**
 * Go to [url] in the kept page and hand back the document once it is there and
 * has held still, or null if it is still a check when the check's patience runs
 * out (or at once for a check only a person can answer: the reader is asked
 * sooner, instead of after a silent wait it was never going to come out of).
 */
private suspend fun navigate(view: WebView, url: String): String? {
    Kept.guard.reset()
    Kept.origin = null
    val asked = System.currentTimeMillis()
    view.loadUrl(url)
    return withTimeoutOrNull(LOAD_MS) {
        // The new document, not the one being left: the page has started, and
        // has got past loading its HTML (or finished altogether).
        while (true) {
            if (Kept.finishedAt >= asked) break
            if (Kept.startedAt >= asked && evaluate(view, "document.readyState") != "loading") break
            delay(150)
        }
        var page = html(view)
        var checkSince = 0L
        var changed = System.currentTimeMillis()
        var settleBy = changed + SETTLE_MAX_MS
        while (true) {
            if (page != null && looksLikeBrowserCheck(page)) {
                val kind = browserCheckKind(page)
                if (kind == CheckKind.Person || kind == CheckKind.Interactive) {
                    Log.i(TAG, "$url: the check wants a person")
                    return@withTimeoutOrNull null
                }
                val now = System.currentTimeMillis()
                if (checkSince == 0L) checkSince = now
                if (now - checkSince > CHECK_PATIENCE_MS) {
                    Log.i(TAG, "$url: still a check after ${CHECK_PATIENCE_MS / 1000} s")
                    return@withTimeoutOrNull null
                }
                delay(500)
                page = html(view)
                // A check that passes reloads into the site, whose page then
                // gets its own time to settle.
                changed = System.currentTimeMillis()
                settleBy = changed + SETTLE_MAX_MS
                continue
            }
            if (System.currentTimeMillis() > settleBy) break
            delay(HELD_STILL_MS / 2)
            val next = html(view)
            if (next == null || next.length != page?.length) {
                page = next
                changed = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - changed >= HELD_STILL_MS) {
                break
            }
        }
        if (Kept.guard.blocked) return@withTimeoutOrNull null
        Kept.origin = originOf(view.url ?: url)
        page
    }
}

/** Loads the page as a browser would (the site sees one) and returns the
 *  document it ends up with. */
// The WebView here is never shown, so an unattended page is fetched like any other.
actual suspend fun fetchThroughBrowser(url: String, publicOnly: Boolean, unattended: Boolean): String? = lock.withLock {
    withContext(Dispatchers.Main) {
        try {
            val view = keptView(publicOnly)
            val origin = originOf(url)
            // Already on this site: ask from inside the page.
            val asked = System.currentTimeMillis()
            if (origin != null && origin == Kept.origin) {
                val asAPage = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                val reply = inPage(view, url, "GET", asAPage, null)
                if (reply != null && reply.status in 200..299 && reply.body.isNotBlank() &&
                    !looksLikeBrowserCheck(reply.body) && !Kept.guard.blocked
                ) {
                    keepCookies(url)
                    Log.i(TAG, "GET $url from inside the page -> ${reply.body.length} chars in ${System.currentTimeMillis() - asked} ms")
                    return@withContext reply.body
                }
                // Refused from inside (a page only handed to a browser going to
                // it, or a check come back): go there instead.
            }
            val page = navigate(view, url) ?: return@withContext null
            // The cookies that got the page through go to the extensions, so
            // the pages after it can be fetched plainly.
            keepCookies(url)
            Log.i(TAG, "GET $url loaded in the page -> ${page.length} chars in ${System.currentTimeMillis() - asked} ms")
            page
        } finally {
            closeLater()
        }
    }
}

/**
 * Keeps a source's page, and whatever it asks for, off this phone and its local
 * network when [on]. The WebView looks names up and follows redirects for
 * itself, so every request it makes is checked here, before it goes out, and a
 * page that tried is not handed back at all. The WebView resolves a name again
 * to connect, so a name that answers differently the second time (DNS
 * rebinding) is the one gap left; it can't say what address it reached.
 */
internal class PublicOnly(val on: Boolean) {
    @Volatile var blocked = false
        private set

    /** A fresh page: what an earlier one tried is not held against it. */
    fun reset() { blocked = false }

    /** Called on the WebView's network thread: null lets the request through. */
    fun check(request: WebResourceRequest): WebResourceResponse? {
        if (!on) return null
        val host = request.url.host ?: return null
        if (!PrivateHosts.isPrivateHost(host)) return null
        blocked = true
        return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), "".byteInputStream())
    }
}

/** Give what the WebView collected to the extensions' cookie jar. The header form
 *  is name/value pairs only, so the domain is the one we asked for. */
internal fun keepCookies(url: String) {
    val http = url.toHttpUrlOrNull() ?: return
    val header = CookieManager.getInstance().getCookie(url).orEmpty()
    val cookies = header.split(';').mapNotNull { pair ->
        val at = pair.indexOf('=')
        if (at <= 0) return@mapNotNull null
        BrowserCookieJar.BrowserCookie(
            name = pair.take(at).trim(),
            value = pair.substring(at + 1).trim(),
            domain = http.host,
            path = "/",
            expiresAt = 0L,
            secure = http.isHttps,
            httpOnly = false,
        )
    }
    if (cookies.isEmpty()) return
    Extensions.cookies.acceptFromBrowser(http, cookies)
}

/** The page as it stands, unescaped from the JavaScript string the WebView returns. */
private suspend fun html(view: WebView): String? = evaluate(view, "document.documentElement.outerHTML")

/**
 * A request made from inside a page of the same site, in the kept WebView: the
 * phone's equivalent of the desktop's driven browser. A chapter list a site
 * fetches with its own script has to be asked for from one of its own pages.
 */
actual suspend fun requestThroughBrowser(
    url: String,
    method: String,
    headers: Map<String, String>,
    body: BrowserBody?,
    publicOnly: Boolean,
): BrowserReply? = lock.withLock {
    val origin = originOf(url) ?: return null
    withContext(Dispatchers.Main) {
        try {
            val view = keptView(publicOnly)
            if (Kept.origin != origin) navigate(view, origin) ?: return@withContext null
            val asked = System.currentTimeMillis()
            val reply = inPage(view, url, method, headers, body)
            if (Kept.guard.blocked) return@withContext null
            if (reply != null) keepCookies(url)
            Log.i(TAG, "$method $url through the page -> ${reply?.status} in ${System.currentTimeMillis() - asked} ms")
            reply
        } finally {
            closeLater()
        }
    }
}

/** [url] fetched by the page in [view], with its cookies. */
private suspend fun inPage(
    view: WebView,
    url: String,
    method: String,
    headers: Map<String, String>,
    body: BrowserBody?,
): BrowserReply? {
    // A form is built in the page, so the browser sets its own boundary, as
    // the site's own script would.
    val makeBody = when (body) {
        null -> "undefined"
        is BrowserBody.Text -> quote(body.value)
        is BrowserBody.Form -> buildString {
            append("(function(){ var f = new FormData(); ")
            for ((name, value) in body.parts) append("f.append(${quote(name)}, ${quote(value)}); ")
            append("return f; })()")
        }
    }
    // evaluateJavascript hands back what an expression is, and a promise is
    // not its answer: the request leaves its result on the page for us to
    // pick up once it has one.
    val script = """
        window.__nsReply = null;
        (async () => {
          try {
            const r = await fetch(${quote(url)}, {
              method: ${quote(method)},
              headers: ${org.json.JSONObject(pageRequestHeaders(headers, body))},
              body: $makeBody,
              credentials: 'include',
            });
            window.__nsReply = r.status + '\u0000' + await r.text();
          } catch (e) {
            window.__nsReply = '0\u0000' + e.message;
          }
        })();
    """.trimIndent()
    evaluate(view, script)
    val reply = withTimeoutOrNull(LOAD_MS) {
        while (true) {
            evaluate(view, "window.__nsReply")?.let { return@withTimeoutOrNull it }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") null
    } ?: return null
    val cut = reply.indexOf('\u0000')
    if (cut < 0) return null
    val status = reply.take(cut).toIntOrNull()?.takeIf { it > 0 } ?: return null
    return BrowserReply(status, reply.substring(cut + 1))
}

private fun quote(value: String): String = org.json.JSONObject.quote(value)

/** Runs script in the page and hands back its value, when it has one. */
private suspend fun evaluate(view: WebView, script: String): String? {
    val result = CompletableDeferred<String?>()
    withContext(Dispatchers.Main) {
        view.evaluateJavascript(script) { raw ->
            result.complete(raw?.takeIf { it.isNotBlank() && it != "null" })
        }
    }
    val raw = withTimeoutOrNull(20_000) { result.await() } ?: return null
    return org.json.JSONTokener(raw).nextValue() as? String
}
