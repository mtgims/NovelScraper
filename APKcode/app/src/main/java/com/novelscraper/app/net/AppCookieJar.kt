package com.novelscraper.app.net

import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Minimal persistent [CookieJar]: keeps cookies (notably the backend's
 * `ns_session`) per host, backed by [SharedPreferences] so the session survives
 * app restarts. A native client can read `Set-Cookie` even when the cookie is
 * httpOnly — that flag only restricts *browser JavaScript*.
 */
class AppCookieJar(private val prefs: SharedPreferences) : CookieJar {

    // host -> (cookie name -> Cookie)
    private val store = HashMap<String, MutableMap<String, Cookie>>()

    init {
        for (entry in prefs.getStringSet(KEY, emptySet()) ?: emptySet()) {
            val sep = entry.indexOf('|')
            if (sep <= 0) continue
            val host = entry.substring(0, sep)
            val raw = entry.substring(sep + 1)
            val url = "https://$host/".toHttpUrlOrNull() ?: continue
            val cookie = Cookie.parse(url, raw) ?: continue
            if (cookie.expiresAt > System.currentTimeMillis()) {
                store.getOrPut(host) { HashMap() }[cookie.name] = cookie
            }
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val map = store.getOrPut(url.host) { HashMap() }
        for (c in cookies) map[c.name] = c
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val map = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        val valid = map.values.filter { it.expiresAt > now && it.matches(url) }
        if (valid.size != map.size) { // prune expired
            map.entries.retainAll { it.value.expiresAt > now }
            persist()
        }
        return valid
    }

    @Synchronized
    fun clear() {
        store.clear()
        prefs.edit().remove(KEY).apply()
    }

    private fun persist() {
        val flat = HashSet<String>()
        for ((host, map) in store) for (c in map.values) flat.add("$host|$c")
        prefs.edit().putStringSet(KEY, flat).apply()
    }

    private companion object {
        const val KEY = "cookies"
    }
}
