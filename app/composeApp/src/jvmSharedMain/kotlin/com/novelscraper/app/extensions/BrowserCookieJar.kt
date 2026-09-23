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
 */
class BrowserCookieJar(private val store: KeyValueStore? = null) : CookieJar {
    private val cookies = ArrayList<Cookie>()

    init {
        store?.getString(KEY, null)?.let { saved ->
            val now = System.currentTimeMillis()
            for (line in saved.split('\n')) {
                val c = decode(line) ?: continue
                if (c.expiresAt > now) cookies += c
            }
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > System.currentTimeMillis()) this.cookies += c
        }
        persist()
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
     *  usually set for ".example.com", and the plugin may ask for either. */
    @Synchronized
    fun acceptFromBrowser(url: HttpUrl, incoming: List<BrowserCookie>) {
        val week = System.currentTimeMillis() + 7 * 24 * 3600_000L
        for (c in incoming) {
            val domain = c.domain?.trim()?.takeIf { it.isNotBlank() } ?: url.host
            val builder = Cookie.Builder()
                .name(c.name)
                .value(c.value)
                .path(c.path?.takeIf { it.startsWith("/") } ?: "/")
                .expiresAt(if (c.expiresAt > System.currentTimeMillis()) c.expiresAt else week)
            // A leading dot is the old way of saying "this domain and below".
            if (domain.startsWith(".")) builder.domain(domain.removePrefix("."))
            else builder.hostOnlyDomain(domain)
            if (c.secure) builder.secure()
            if (c.httpOnly) builder.httpOnly()
            val cookie = builder.build()
            cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            cookies += cookie
        }
        persist()
    }

    /** Forget everything this host set, so its check runs again. Used when a site
     *  starts refusing us again: the clearance was tied to an address we no
     *  longer have (a VPN that moved), and sending it only wastes a request. */
    @Synchronized
    fun forget(host: String) {
        cookies.removeAll { host == it.domain || host.endsWith("." + it.domain) }
        persist()
    }

    /** Written as one line per cookie, which is all Set-Cookie ever was. */
    private fun persist() {
        val store = store ?: return
        val now = System.currentTimeMillis()
        store.putString(KEY, cookies.filter { it.expiresAt > now }.joinToString("\n") { encode(it) })
    }

    private fun encode(c: Cookie): String = listOf(
        c.name, c.value, c.domain, c.path, c.expiresAt.toString(),
        c.secure.toString(), c.httpOnly.toString(), c.hostOnly.toString(),
    ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }

    private fun decode(line: String): Cookie? {
        val f = line.split('\t')
        if (f.size < 8) return null
        return runCatching {
            Cookie.Builder()
                .name(f[0]).value(f[1]).path(f[3])
                .expiresAt(f[4].toLong())
                .apply {
                    if (f[7].toBoolean()) hostOnlyDomain(f[2]) else domain(f[2])
                    if (f[5].toBoolean()) secure()
                    if (f[6].toBoolean()) httpOnly()
                }
                .build()
        }.getOrNull()
    }

    private companion object { const val KEY = "site-cookies" }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }
}
