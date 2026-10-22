package com.novelscraper.app.net

import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.settingsStore
import okhttp3.OkHttpClient

/**
 * Process-wide networking holder: the one [client] that images, updates, voice
 * downloads and WebDAV build on. Initialised once at startup.
 */
object Net {
    @Volatile lateinit var client: OkHttpClient
        private set

    fun init() {
        client = OkHttpClient.Builder().build()
        forgetServer()
    }

    /**
     * Up to 1.1, an account on the NovelScraper server kept its address, the
     * user name and the session cookie here. The server is gone; the session
     * should not outlive it on the device.
     */
    internal fun forgetServer(old: () -> KeyValueStore = { settingsStore("ns") }) {
        runCatching { old().let { store -> store.keys().forEach(store::remove) } }
    }

    /** Resolve an <img src> from stored chapter HTML to a loadable URL, or null
     *  if it can't be shown. Absolute http(s) and data URLs are kept (a source
     *  site's illustrations); anything relative has no base to resolve against,
     *  so it is dropped. */
    fun contentImageUrl(src: String): String? {
        val s = src.trim()
        return when {
            s.isEmpty() -> null
            s.startsWith("http://") || s.startsWith("https://") || s.startsWith("data:") -> s
            else -> null
        }
    }
}
