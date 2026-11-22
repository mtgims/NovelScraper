package com.novelscraper.app.extensions

import com.novelscraper.app.platform.canPassSiteChecks
import com.novelscraper.app.platform.passSiteCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sites that asked for a browser check, and the app's answer to them.
 *
 * A plugin's request coming back as a check ([SiteChallengeException]) records
 * the address here; the app then offers to open it in a real browser (a WebView
 * on the phone, Chromium on the desktop). What the browser collects goes to the
 * extensions' cookie jar, so the next request goes through like any other.
 */
object SiteChecks {

    private val _pending = MutableStateFlow<String?>(null)
    /** The address of a site waiting for a check, if any. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    /** What the check is doing right now (null when it isn't running). */
    val status: StateFlow<String?> = _status.asStateFlow()

    val possible: Boolean get() = canPassSiteChecks

    private val _inApp = MutableStateFlow<String?>(null)
    /** The check the reader has open in the app's own web view, if any. */
    val inApp: StateFlow<String?> = _inApp.asStateFlow()

    /** A source answered with a browser check. Nothing is asked while a check
     *  is already being worked through: a second prompt over it would only be
     *  in the way of the one on screen. */
    fun needed(url: String) {
        if (_status.value == null && _inApp.value == null) _pending.value = url
    }

    /** The reader opened the check at [url] in the app's web view. */
    fun beginInApp(url: String) {
        _inApp.value = url
        _pending.value = null
    }

    private val _passed = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    /**
     * The address of each check passed, in the app's web view or the computer's
     * browser, once what the site set is with the sources. Lists stopped by
     * that site's check load again on hearing it, even from under the screen
     * that passed it.
     */
    val passed: kotlinx.coroutines.flow.SharedFlow<String> = _passed

    /** The site at [url] let the app in, and its cookies are already with the sources. */
    fun passed(url: String) { _passed.tryEmit(url) }

    /** True when [a] and [b] are the same site: the same host, give or take "www." or a subdomain. */
    fun sameSite(a: String?, b: String?): Boolean {
        val x = a?.let(::host)?.removePrefix("www.") ?: return false
        val y = b?.let(::host)?.removePrefix("www.") ?: return false
        return x == y || x.endsWith(".$y") || y.endsWith(".$x")
    }

    /** The web view with the check closed, passed or not. Whatever asked meanwhile
     *  was asking for the same check, so nothing is left waiting. */
    fun endInApp() {
        _inApp.value = null
        _pending.value = null
    }

    // Sites that refuse this app's plain requests, so their pages are loaded in
    // the browser from the start. Written down, because a site that wanted a
    // browser yesterday wants one today, and finding that out again costs a
    // refused request and a page load every time the app starts.
    private val store by lazy { com.novelscraper.app.platform.settingsStore("site-checks") }
    private val browserHosts: MutableSet<String> by lazy {
        java.util.Collections.synchronizedSet(HashSet(store.getStringSet("browser-hosts").orEmpty()))
    }

    /** Remember that this site only answers a real browser. */
    fun needsBrowser(url: String) {
        host(url)?.let {
            // A plain request has just been refused: that is this half hour's
            // try, not a reason to make another straight away.
            triedPlainly[it] = System.currentTimeMillis()
            if (browserHosts.add(it)) save()
        }
    }

    // When each host was last given another chance at a plain request.
    private val triedPlainly = java.util.Collections.synchronizedMap(HashMap<String, Long>())
    private const val TRY_PLAIN_AGAIN_MS = 30 * 60_000L

    /** True if this site's pages should go straight through the browser.
     *
     *  Every half hour a host gets one plain request again: a guard can be
     *  lifted, or have been a passing mood, and a site written down as needing a
     *  browser would otherwise take the slow way round for ever. */
    fun wantsBrowser(url: String): Boolean {
        val host = host(url) ?: return false
        if (host !in browserHosts) return false
        // The browser has just got in: what it was let in with may carry plain
        // requests too (DDoS-Guard's cookies do, Cloudflare's don't), and a
        // plain request is a fraction of a second where a page in the browser
        // is several. One try says which.
        if (afterPass.remove(host)) {
            onTrial.add(host)
            return false
        }
        val now = System.currentTimeMillis()
        if (now - (triedPlainly[host] ?: 0L) > TRY_PLAIN_AGAIN_MS) {
            triedPlainly[host] = now
            return false
        }
        return true
    }

    // Sites whose check has needed a person before. Their window is put on screen
    // straight away next time, rather than spending a silent minute on a check
    // that was never going to pass by itself.
    private val interactiveHosts: MutableSet<String> by lazy {
        java.util.Collections.synchronizedSet(HashSet(store.getStringSet("interactive-hosts").orEmpty()))
    }

    /** This site's check wanted a person. */
    fun neededPerson(url: String) {
        host(url)?.let { if (interactiveHosts.add(it)) save() }
    }

    /** True if this site has asked for a person before. */
    fun wantsPerson(url: String): Boolean = host(url)?.let { it in interactiveHosts } == true

    /** This site's check passed on its own this time: it has stopped wanting a
     *  person, so its window stays out of sight from here on. A check asks for
     *  one under its own conditions, not for ever, and a site written down as
     *  wanting one would otherwise put a window on screen at every request. */
    fun passedAlone(url: String) {
        host(url)?.let { if (interactiveHosts.remove(it)) save() }
    }

    private fun save() {
        store.putStringSet("browser-hosts", synchronized(browserHosts) { HashSet(browserHosts) })
        store.putStringSet("interactive-hosts", synchronized(interactiveHosts) { HashSet(interactiveHosts) })
    }

    /** This site answers plain requests again (its guard was lifted, or the one
     *  page that needed a browser was an accident): stop routing it the long way. */
    fun plainAgain(url: String) {
        host(url)?.let {
            onTrial.remove(it)
            if (browserHosts.remove(it)) save()
        }
    }

    // Sites the browser has just been let into, whose next request goes plainly
    // to see whether what the browser was given carries it (see wantsBrowser).
    private val afterPass: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    // Sites whose plain request is that try, until it is answered.
    private val onTrial: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    // Sites where it didn't: their passes hold only for the browser. Not tried
    // again this run, or every other request would be a refusal.
    private val passStaysInBrowser: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** The browser brought back a page of this site. */
    fun browserGotIn(url: String) {
        val host = host(url) ?: return
        if (host in browserHosts && host !in passStaysInBrowser) afterPass.add(host)
    }

    /** Everything known about this site's checks, gone (tests share one host). */
    internal fun forget(url: String) {
        val host = host(url) ?: return
        listOf(afterPass, onTrial, passStaysInBrowser).forEach { it.remove(host) }
        triedPlainly.remove(host)
        interactiveHosts.remove(host)
        if (browserHosts.remove(host)) save()
    }

    /** A plain request to this site was turned away. */
    fun plainRefused(url: String) {
        host(url)?.let { if (onTrial.remove(it)) passStaysInBrowser.add(it) }
    }

    private fun host(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()

    fun dismiss() {
        _pending.value = null
    }

    /** Open the browser and wait for the site to let us through. */
    suspend fun run(): Boolean {
        val url = _pending.value ?: return false
        _status.value = "Starting…"
        return try {
            val ok = passSiteCheck(url) { _status.value = it }
            if (ok) { _pending.value = null; passed(url) }
            ok
        } finally {
            _status.value = null
        }
    }
}
