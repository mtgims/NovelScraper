package com.novelscraper.app.sync

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleDriveFolderTest {

    private val google = FakeGoogle()
    private val http = OkHttpClient()

    @AfterTest
    fun stop() = google.close()

    private fun oauth() = GoogleDriveFolder.oauth(http, base = google.base, authBase = google.base)

    /** A browser that goes to the sign-in page and comes back as Google would:
     *  first a stray request (a favicon), then the redirect with [query]. */
    private fun browser(query: (state: String) -> String): (String) -> Unit = { page ->
        val url = page.toHttpUrl()
        google.challenge = url.queryParameter("code_challenge")
        val redirect = url.queryParameter("redirect_uri")!!
        val state = url.queryParameter("state")!!
        thread {
            runCatching { http.newCall(Request.Builder().url("$redirect/favicon.ico").build()).execute().close() }
            http.newCall(Request.Builder().url("$redirect/?${query(state)}").build()).execute().close()
        }
    }

    private suspend fun signedIn(): OAuthSession {
        val tokens = oauth().authorize(browser { "state=$it&code=good-code&scope=x" })
        return OAuthSession(oauth(), tokens.refresh!!).also { it.seed(tokens) }
    }

    @Test
    fun signingInAsksOnlyForTheAppFolderAndProvesTheVerifier() = runBlocking<Unit> {
        var page = ""
        val tokens = oauth().authorize(openBrowser = { page = it; browser { s -> "state=$s&code=good-code" }(it) })
        val url = page.toHttpUrl()
        assertEquals("$SCOPE openid email", url.queryParameter("scope"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("offline", url.queryParameter("access_type"))
        assertTrue(url.queryParameter("redirect_uri")!!.startsWith("http://127.0.0.1:"))
        assertEquals("rt-1", tokens.refresh)
        assertEquals("reader@example.com", OAuthClient.idTokenClaim(tokens.idToken, "email"))
        // The fake refuses a code_verifier that does not match the challenge, so
        // getting here proves PKCE end to end.
        assertEquals("authorization_code", google.tokenRequests.single()["grant_type"])
    }

    @Test
    fun aRefusalAtTheSignInPageIsSaidAsSuch() = runBlocking<Unit> {
        assertFailsWith<OAuthDeniedException> {
            oauth().authorize(browser { "state=$it&error=access_denied" })
        }
    }

    @Test
    fun aRedirectForAnotherSignInIsIgnored() = runBlocking<Unit> {
        assertFailsWith<OAuthTimeoutException> {
            oauth().authorize(browser { "state=someone-elses&code=good-code" }, timeoutMs = 1_500)
        }
        assertTrue(google.tokenRequests.isEmpty(), "a code under the wrong state is never traded")
    }

    @Test
    fun theFolderBehavesAsSyncNeedsIt() = runBlocking<Unit> {
        val f = GoogleDriveFolder(signedIn(), http, base = google.base)
        assertTrue(f.list().isEmpty())
        val names = (1..5).map { "dev.000000000$it.seg" }
        names.forEach { f.write(it, it.toByteArray()) }
        f.write(SyncFiles.MANIFEST, "{}".toByteArray())
        assertEquals((names + SyncFiles.MANIFEST).toSet(), f.list().map { it.name }.toSet(), "every page listed")
        assertContentEquals(names[2].toByteArray(), f.read(names[2]))

        f.write(names[2], byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), f.read(names[2]), "a write replaces")
        assertEquals(6, google.files.size, "replacing does not add a file")

        f.delete(names[2])
        assertNull(f.read(names[2]))
        f.delete(names[2])
        assertEquals(5, f.list().size)
        // A fresh instance (another run of the app) finds files by name too.
        val again = GoogleDriveFolder(signedIn(), http, base = google.base)
        assertContentEquals(names[0].toByteArray(), again.read(names[0]))
    }

    @Test
    fun anExpiredTokenIsRenewedAndARevokedSignInIsSaid() = runBlocking<Unit> {
        val session = signedIn()
        val f = GoogleDriveFolder(session, http, base = google.base)
        f.write("a.0000000001.seg", byteArrayOf(1))
        google.validTokens.clear()   // the access token ran out
        assertEquals(1, f.list().size)
        assertEquals(2, google.tokensIssued, "renewed once")

        google.validTokens.clear(); google.validRefresh.clear()   // access withdrawn
        assertFailsWith<FolderAuthException> { f.list() }
    }

    @Test
    fun duplicateNamesResolveTheSameWayEverywhere() = runBlocking<Unit> {
        // Two devices set the folder up at once, and a retried upload landed twice.
        google.put(SyncFiles.MANIFEST, "first".toByteArray())
        google.put(SyncFiles.MANIFEST, "second".toByteArray())
        google.put("d.0000000001.seg", "old".toByteArray())
        google.put("d.0000000001.seg", "new".toByteArray())
        for (device in 1..2) {
            val f = GoogleDriveFolder(signedIn(), http, base = google.base)
            val listed = f.list()
            assertEquals(1, listed.count { it.name == SyncFiles.MANIFEST })
            assertEquals("first", String(f.read(SyncFiles.MANIFEST)!!), "the manifest: lowest id")
            assertEquals("new", String(f.read("d.0000000001.seg")!!), "anything else: newest")
        }
    }

    private companion object {
        const val SCOPE = GoogleDriveFolder.SCOPE
    }
}
