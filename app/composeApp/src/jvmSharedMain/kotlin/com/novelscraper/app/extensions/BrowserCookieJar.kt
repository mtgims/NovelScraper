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

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }
}
