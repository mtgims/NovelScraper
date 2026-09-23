package com.novelscraper.app.extensions

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * The cookies source sites set while extensions browse them, kept like a browser
 * keeps them: sent back to matching domains and paths until they expire. Held in
 * memory for the app's lifetime (sessions, Cloudflare clearance), separate from
 * the NovelScraper server's login cookie.
 */
class BrowserCookieJar : CookieJar {
    private val cookies = ArrayList<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > System.currentTimeMillis()) this.cookies += c
        }
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
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }
}
