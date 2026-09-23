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

    /** Cookies a real browser collected (passing a site's check), as
     *  "name=value" pairs for [url]'s host. */
    @Synchronized
    fun acceptFromBrowser(url: HttpUrl, pairs: List<Pair<String, String>>) {
        for ((name, value) in pairs) {
            val cookie = Cookie.Builder()
                .name(name).value(value)
                .domain(url.host)
                .path("/")
                .expiresAt(System.currentTimeMillis() + 7 * 24 * 3600_000L)
                .build()
            cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain }
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
