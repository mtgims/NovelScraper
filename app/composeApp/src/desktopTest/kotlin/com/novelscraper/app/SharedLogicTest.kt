package com.novelscraper.app

import com.novelscraper.app.net.AppCookieJar
import com.novelscraper.app.net.detail
import com.novelscraper.app.platform.KeyValueStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared (jvmSharedMain) logic that used Android APIs before the move. */
class SharedLogicTest {

    // --- HttpException.detail (was org.json) ----------------------------------

    private fun httpError(code: Int, body: String): HttpException =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    @Test fun detailString() =
        assertEquals("Invalid credentials", httpError(401, """{"detail":"Invalid credentials"}""").detail())

    @Test fun detailMissingOrBlankOrNotJson() {
        assertNull(httpError(500, """{"error":"x"}""").detail())
        assertNull(httpError(400, """{"detail":"  "}""").detail())
        assertNull(httpError(502, "<html>Bad gateway</html>").detail())
    }

    @Test fun detailNonStringComesBackAsJson() {
        val d = httpError(422, """{"detail":[{"loc":["body","url"],"msg":"field required"}]}""").detail()
        assertEquals("""[{"loc":["body","url"],"msg":"field required"}]""", d)
    }

    // --- AppCookieJar over a KeyValueStore (was SharedPreferences) -------------

    private class MemStore : KeyValueStore {
        val sets = HashMap<String, Set<String>>()
        override fun getString(key: String, default: String?) = default
        override fun getStringSet(key: String) = sets[key]
        override fun getFloat(key: String, default: Float) = default
        override fun getInt(key: String, default: Int) = default
        override fun getBoolean(key: String, default: Boolean) = default
        override fun putString(key: String, value: String) {}
        override fun putStringSet(key: String, value: Set<String>) { sets[key] = value.toSet() }
        override fun putFloat(key: String, value: Float) {}
        override fun putInt(key: String, value: Int) {}
        override fun putBoolean(key: String, value: Boolean) {}
        override fun remove(key: String) { sets.remove(key) }
    }

    @Test fun cookiesSurviveARestartAndClear() {
        val store = MemStore()
        val url = "https://novelscraper.example/api/auth/login".toHttpUrl()
        val far = System.currentTimeMillis() + 86_400_000L
        val cookie = okhttp3.Cookie.Builder().domain("novelscraper.example").path("/")
            .name("ns_session").value("abc123").expiresAt(far).httpOnly().secure().build()

        AppCookieJar(store).saveFromResponse(url, listOf(cookie))
        // Same format the Android build has always written: "host|<Set-Cookie>".
        assertTrue(store.sets["cookies"]!!.single().startsWith("novelscraper.example|ns_session=abc123"))

        val restarted = AppCookieJar(store)
        val sent = restarted.loadForRequest("https://novelscraper.example/api/books".toHttpUrl())
        assertEquals(listOf("ns_session" to "abc123"), sent.map { it.name to it.value })

        restarted.clear()
        assertNull(store.sets["cookies"])
        assertTrue(AppCookieJar(store).loadForRequest(url).isEmpty())
    }
}
