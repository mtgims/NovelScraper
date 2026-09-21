package com.novelscraper.app.net

import com.novelscraper.app.data.ChapterRead
import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.isDebugBuild
import com.novelscraper.app.platform.settingsStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Process-wide networking holder. Owns the base URL (persisted), the cookie jar
 * (persisted session), and a Retrofit [Api]. Rebuilt when the base URL changes.
 * Initialised once from [com.novelscraper.app.App].
 */
object Net {
    const val DEFAULT_BASE_URL = "https://novelscraper.com/"

    private lateinit var prefs: KeyValueStore
    lateinit var cookieJar: AppCookieJar
        private set

    @Volatile private var _baseUrl: String = DEFAULT_BASE_URL
    @Volatile private lateinit var _api: Api
    @Volatile lateinit var client: OkHttpClient      // shared with Coil (carries the cookie)
        private set

    val baseUrl: String get() = _baseUrl
    val api: Api get() = _api

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun init() {
        prefs = settingsStore("ns")
        cookieJar = AppCookieJar(prefs)
        // Always normalise (guarantee a trailing slash) so string-built URLs like
        // coverUrl()/imageUrl() are correct even for the default/persisted value.
        _baseUrl = normalize(prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
        rebuild()
    }

    /** Change the backend address (e.g. a LAN/self-hosted server). Persists and
     *  rebuilds the client. Returns the normalised URL. */
    fun setBaseUrl(url: String): String {
        val normalized = normalize(url)
        _baseUrl = normalized
        prefs.putString(KEY_BASE_URL, normalized)
        rebuild()
        return normalized
    }

    private fun rebuild() {
        val builder = OkHttpClient.Builder().cookieJar(cookieJar)
        // Request logging is a debug aid, not something to run in release: it
        // formats and writes a logcat line for every request and response —
        // including every cover and chapter image Coil pulls through this same
        // client — and puts the user's library activity in the device log.
        if (isDebugBuild) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }
        client = builder.build()
        _api = Retrofit.Builder()
            .baseUrl(_baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(Api::class.java)
    }

    // --- URL helpers for image loads (Coil) that must carry the cookie ---

    /** Widths the server will render a cover at (backend THUMB_WIDTHS). Asking
     *  for anything else serves the source image, which can be over a megabyte. */
    const val COVER_WIDTH = 800

    /** URL for a book's cover, capped at [COVER_WIDTH] px wide.
     *
     *  Covers are stored at whatever resolution the source site published — the
     *  test library has a 1.4MB and an 805KB JPEG — while the grid draws them a
     *  few hundred px wide and the book screen not much more. 800px covers the
     *  largest render on a high-density phone with no visible softening, and is
     *  ~80% fewer bytes over the user's mobile data. An older server that
     *  doesn't know `w` ignores it and serves the original, so this degrades
     *  rather than breaking. */
    fun coverUrl(bookId: Int): String =
        "${_baseUrl}api/books/$bookId/cover?w=$COVER_WIDTH"
    fun imageUrl(bookId: Int, name: String): String =
        "${_baseUrl}api/books/$bookId/images/$name"

    /** Resolve an <img src> from stored chapter HTML to a loadable URL (fetched
     *  through the shared cookie-carrying client via Coil), or null if it can't
     *  be shown. Mirrors the web reader: server-stored illustrations ("/api/…")
     *  become absolute; already-absolute http(s)/data URLs are kept; anything
     *  else (relative/broken) is dropped. */
    fun contentImageUrl(src: String): String? {
        val s = src.trim()
        return when {
            s.isEmpty() -> null
            s.startsWith("/") -> _baseUrl.trimEnd('/') + s
            s.startsWith("http://") || s.startsWith("https://") || s.startsWith("data:") -> s
            else -> null
        }
    }

    private fun normalize(url: String): String {
        var u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        if (!u.endsWith("/")) u += "/"
        return u
    }

    private const val KEY_BASE_URL = "base_url"
}
