package com.novelscraper.app.extensions

import com.novelscraper.app.platform.KeyValueStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * The cookies source sites set while extensions browse them, kept like a browser
 * keeps them: sent back to matching domains and paths until they expire,
 * separate from the NovelScraper server's login cookie.
 *
 * Given a [store], they are written down and read back on the next run. What a
 * browser check earns (Cloudflare's clearance, DDoS-Guard's pass) is good for
 * days, and throwing it away when the app closes means being asked again every
 * single launch.
 *
 * **Cookies are owned.** A browser scopes cookies by site, because its only
 * principal is the person using it. Here there is a second principal, the
 * plugin, and a single shared jar does not model it: a source asking for a site
 * that another source had logged into got that session attached by the jar, and
 * could read the reply. It never had to touch the cookie, which is why keeping
 * plugins out of each other's storage does not help.
 *
 * So each plugin fetches through [forPlugin] and sees only what it set itself,
 * plus the shared pool. The shared pool is what a real browser earned on the
 * reader's behalf, through [acceptFromBrowser]: clearance is not a credential,
 * it says "not a bot", and it costs a captcha to replace. Isolating that too
 * would mean every plugin passing its own challenge for the same site.
 */
class BrowserCookieJar(private val store: KeyValueStore? = null) : CookieJar {

    /** [owner] null means the shared pool: earned by a browser check. */
    private data class Owned(val owner: String?, val cookie: Cookie)

    private val cookies = ArrayList<Owned>()

    init {
        store?.getString(KEY, null)?.let { saved ->
            val now = System.currentTimeMillis()
            for (line in saved.split('\n')) {
                val o = decode(line) ?: continue
                if (o.cookie.expiresAt > now) cookies += o
            }
        }
    }

    @Synchronized
    private fun save(owner: String?, incoming: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (c in incoming) {
            cookies.removeAll {
                it.owner == owner && it.cookie.name == c.name &&
                    it.cookie.domain == c.domain && it.cookie.path == c.path
            }
            if (c.expiresAt > now) cookies += Owned(owner, c)
        }
        persist()
    }

    @Synchronized
    private fun load(owner: String?, url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.cookie.expiresAt <= now }
        return cookies.filter { (it.owner == null || it.owner == owner) && it.cookie.matches(url) }
            .map { it.cookie }
    }

    /** The app's own fetches (repository indexes, plugin code), which are not a
     *  plugin and should not be able to borrow one's session either. */
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = save(APP, cookies)
    override fun loadForRequest(url: HttpUrl): List<Cookie> = load(APP, url)

    /** A view for one plugin: it sets its own cookies and reads its own, plus
     *  whatever a browser check earned for everyone. */
    fun forPlugin(pluginId: String): CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = save(pluginId, cookies)
        override fun loadForRequest(url: HttpUrl): List<Cookie> = load(pluginId, url)
    }

    /** One cookie as a real browser holds it. */
    data class BrowserCookie(
        val name: String,
        val value: String,
        /** The cookie's own domain; a leading dot means "and its subdomains". */
        val domain: String?,
        val path: String?,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
    )

    /** Take cookies a real browser collected (passing a site's check), keeping the
     *  domain and path it set them for: a check earned on "www.example.com" is
     *  usually set for ".example.com", and the plugin may ask for either.
     *  These go to the shared pool, so one captcha serves every plugin. */
    fun acceptFromBrowser(url: HttpUrl, incoming: List<BrowserCookie>) {
        val week = System.currentTimeMillis() + 7 * 24 * 3600_000L
        val built = incoming.map { c ->
            val domain = c.domain?.trim()?.takeIf { it.isNotBlank() } ?: url.host
            Cookie.Builder()
                .name(c.name)
                .value(c.value)
                .path(c.path?.takeIf { it.startsWith("/") } ?: "/")
                .expiresAt(if (c.expiresAt > System.currentTimeMillis()) c.expiresAt else week)
                .apply {
                    // A leading dot is the old way of saying "this domain and below".
                    if (domain.startsWith(".")) domain(domain.removePrefix(".")) else hostOnlyDomain(domain)
                    if (c.secure) secure()
                    if (c.httpOnly) httpOnly()
                }
                .build()
        }
        save(null, built)
    }

    /** Forget everything this host set, whoever owns it, so its check runs again.
     *  Used when a site starts refusing us again: the clearance was tied to an
     *  address we no longer have (a VPN that moved), and sending it only wastes
     *  a request. */
    @Synchronized
    fun forget(host: String) {
        cookies.removeAll { host == it.cookie.domain || host.endsWith("." + it.cookie.domain) }
        persist()
    }

    /** Written as one line per cookie, which is all Set-Cookie ever was, with
     *  the owner in front. */
    private fun persist() {
        val store = store ?: return
        val now = System.currentTimeMillis()
        store.putString(KEY, cookies.filter { it.cookie.expiresAt > now }
            .joinToString("\n") { encode(it) })
    }

    private fun encode(o: Owned): String = listOf(
        o.owner ?: "", o.cookie.name, o.cookie.value, o.cookie.domain, o.cookie.path,
        o.cookie.expiresAt.toString(), o.cookie.secure.toString(),
        o.cookie.httpOnly.toString(), o.cookie.hostOnly.toString(),
    ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }

    private fun decode(line: String): Owned? {
        val f = line.split('\t')
        if (f.size < 9) return null
        return runCatching {
            val cookie = Cookie.Builder()
                .name(f[1]).value(f[2]).path(f[4])
                .expiresAt(f[5].toLong())
                .apply {
                    if (f[8].toBoolean()) hostOnlyDomain(f[3]) else domain(f[3])
                    if (f[6].toBoolean()) secure()
                    if (f[7].toBoolean()) httpOnly()
                }
                .build()
            Owned(f[0].takeIf { it.isNotEmpty() }, cookie)
        }.getOrNull()
    }

    private companion object {
        /** Owner for the app's own requests, which no plugin id can collide with. */
        const val APP = "\u0000app"

        /** Deliberately not the old key. Cookies written before ownership existed
         *  cannot say who earned them, and guessing would hand every plugin
         *  whatever sessions were already there, which is the bug. They are left
         *  behind: one round of browser checks, and any site login set again. */
        const val KEY = "site-cookies-owned"
    }
}
