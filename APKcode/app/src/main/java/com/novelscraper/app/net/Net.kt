package com.novelscraper.app.net

import android.content.Context
import android.content.SharedPreferences
import com.novelscraper.app.data.ChapterRead
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
    const val DEFAULT_BASE_URL = "https://novelscraper.com"

    private lateinit var prefs: SharedPreferences
    lateinit var cookieJar: AppCookieJar
        private set

    @Volatile private var _baseUrl: String = DEFAULT_BASE_URL
    @Volatile private lateinit var _api: Api

    val baseUrl: String get() = _baseUrl
    val api: Api get() = _api

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("ns", Context.MODE_PRIVATE)
        cookieJar = AppCookieJar(prefs)
        _baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        rebuild()
    }

    /** Change the backend address (e.g. a LAN/self-hosted server). Persists and
     *  rebuilds the client. Returns the normalised URL. */
    fun setBaseUrl(url: String): String {
        val normalized = normalize(url)
        _baseUrl = normalized
        prefs.edit().putString(KEY_BASE_URL, normalized).apply()
        rebuild()
        return normalized
    }

    private fun rebuild() {
        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
        _api = Retrofit.Builder()
            .baseUrl(_baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(Api::class.java)
    }

    // --- URL helpers for image loads (Coil) that must carry the cookie ---
    fun coverUrl(bookId: Int): String = "${_baseUrl}api/books/$bookId/cover"
    fun imageUrl(bookId: Int, name: String): String =
        "${_baseUrl}api/books/$bookId/images/$name"

    private fun normalize(url: String): String {
        var u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        if (!u.endsWith("/")) u += "/"
        return u
    }

    private const val KEY_BASE_URL = "base_url"
}
