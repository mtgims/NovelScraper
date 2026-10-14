package com.novelscraper.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The person declined, or the provider refused, at the sign-in page. */
class OAuthDeniedException(message: String) : Exception(message)

/** Nobody finished signing in in the browser in time. */
class OAuthTimeoutException : Exception("Signing in took too long.")

/** What a token endpoint gave: [refresh] only when it issues one. */
internal data class OAuthTokens(
    val access: String,
    val refresh: String?,
    val expiresAt: Long,
    val idToken: String?,
)

/**
 * Signing in to a storage provider the way an installed app may: the
 * authorization code flow with PKCE (RFC 7636), the browser coming back to a
 * loopback address on this device (RFC 8252). Nothing but a client id is built
 * into the app, and the same code runs on Android and desktop.
 *
 * [ports]: the loopback ports to try, for providers that only accept redirect
 * addresses registered in advance (Dropbox); empty takes any free one.
 */
internal class OAuthClient(
    private val authUrl: String,
    private val tokenUrl: String,
    private val clientId: String,
    private val clientSecret: String?,
    private val scopes: List<String>,
    private val authParams: Map<String, String> = emptyMap(),
    private val ports: List<Int> = emptyList(),
    private val http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Send the person to the provider's sign-in page ([openBrowser]) and wait
     * for it to send them back with a code, then trade the code for tokens.
     * Cancelling the coroutine stops the wait. [returnLink]: where the page
     * that ends the sign-in points to get back to the app, if anywhere.
     */
    suspend fun authorize(
        openBrowser: (String) -> Unit,
        returnLink: String? = null,
        timeoutMs: Long = 5 * 60_000L,
    ): OAuthTokens {
        val verifier = randomToken(48)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomToken(24)
        return withContext(Dispatchers.IO) {
            listen().use { server ->
                val redirect = "http://127.0.0.1:${server.localPort}"
                val url = authUrl.toHttpUrl().newBuilder()
                    .addQueryParameter("client_id", clientId)
                    .addQueryParameter("redirect_uri", redirect)
                    .addQueryParameter("response_type", "code")
                    .addQueryParameter("scope", scopes.joinToString(" "))
                    .addQueryParameter("code_challenge", challenge)
                    .addQueryParameter("code_challenge_method", "S256")
                    .addQueryParameter("state", state)
                    .apply { authParams.forEach { (k, v) -> addQueryParameter(k, v) } }
                    .build()
                openBrowser(url.toString())
                val code = awaitCode(server, state, now() + timeoutMs, returnLink)
                token(
                    FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("redirect_uri", redirect)
                        .add("code_verifier", verifier),
                    previousRefresh = null,
                )
            }
        }
    }

    /** New access token from a refresh token. Throws [FolderAuthException]
     *  when the provider no longer honours it (revoked, expired). */
    suspend fun refresh(refreshToken: String): OAuthTokens = withContext(Dispatchers.IO) {
        token(
            FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken),
            previousRefresh = refreshToken,
        )
    }

    /** Loopback only: nothing off this device can reach it. */
    private fun listen(): ServerSocket {
        val loopback = InetAddress.getByName("127.0.0.1")
        if (ports.isEmpty()) return ServerSocket(0, 1, loopback)
        for (p in ports) {
            try { return ServerSocket(p, 1, loopback) } catch (_: IOException) {}
        }
        throw IOException("none of the sign-in ports (${ports.joinToString()}) is free")
    }

    /**
     * Answer requests on [server] until one carries this sign-in's [state]: its
     * code, or the provider's refusal. Anything else (a favicon request, a
     * stale tab) gets a 404 and the wait goes on.
     */
    private suspend fun awaitCode(server: ServerSocket, state: String, deadline: Long, returnLink: String?): String {
        server.soTimeout = 500
        while (true) {
            currentCoroutineContext().ensureActive()
            if (now() > deadline) throw OAuthTimeoutException()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use { s ->
                s.soTimeout = 5_000
                val line = runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
                val target = line.split(' ').getOrNull(1).orEmpty()
                val url = "http://127.0.0.1$target".toHttpUrlOrNull()
                val ours = url != null && url.queryParameter("state") == state
                val code = url?.queryParameter("code")
                val error = url?.queryParameter("error")
                val (status, page) = when {
                    !ours -> "404 Not Found" to ""
                    code != null -> "200 OK" to donePage(returnLink)
                    else -> "200 OK" to PAGE_FAILED
                }
                s.getOutputStream().write(
                    ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${page.toByteArray().size}\r\nConnection: close\r\n\r\n$page").toByteArray()
                )
                if (ours) {
                    if (code != null) return code
                    throw OAuthDeniedException(
                        url?.queryParameter("error_description") ?: error ?: "the sign-in did not finish"
                    )
                }
            }
        }
    }

    private fun token(form: FormBody.Builder, previousRefresh: String?): OAuthTokens {
        form.add("client_id", clientId)
        clientSecret?.takeIf { it.isNotEmpty() }?.let { form.add("client_secret", it) }
        val req = Request.Builder().url(tokenUrl).post(form.build()).build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (!r.isSuccessful) {
                val error = obj?.str("error")
                // invalid_grant: the refresh token was revoked or has expired, and
                // only signing in again helps.
                if (r.code in 400..401 && (error == "invalid_grant" || previousRefresh != null))
                    throw FolderAuthException("the sign-in is no longer valid ($error)")
                throw IOException("the token endpoint answered ${r.code}" + (error?.let { ": $it" } ?: ""))
            }
            val access = obj?.str("access_token") ?: throw IOException("no access token in the answer")
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
            return OAuthTokens(
                access = access,
                // A provider may rotate the refresh token, or leave it as it was.
                refresh = obj.str("refresh_token") ?: previousRefresh,
                expiresAt = now() + expiresIn * 1000,
                idToken = obj.str("id_token"),
            )
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val random = SecureRandom()

        private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.takeIf { it.isString }?.content

        private fun randomToken(bytes: Int) = b64url(ByteArray(bytes).also(random::nextBytes))
        private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        /** A claim from an OpenID id_token's payload. Not verified: it came
         *  straight from the provider's token endpoint over TLS, and is only
         *  shown to the person (which account they connected). */
        fun idTokenClaim(idToken: String?, claim: String): String? {
            val payload = idToken?.split('.')?.getOrNull(1) ?: return null
            val text = runCatching { String(Base64.getUrlDecoder().decode(payload.trimEnd('='))) }.getOrNull()
                ?: return null
            return runCatching { json.parseToJsonElement(text).jsonObject.str(claim) }.getOrNull()
        }

        private const val PAGE_STYLE =
            "<style>body{font-family:sans-serif;background:#16161a;color:#e8e8ee;display:flex;" +
                "align-items:center;justify-content:center;height:90vh;text-align:center}</style>"
        private fun donePage(returnLink: String?) =
            "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
                "<title>NovelScraper</title>$PAGE_STYLE<div><h2>Signed in</h2>" +
                (returnLink?.let { "<p><a style='color:#9aa0ff;font-size:1.2em' href=\"$it\">Back to NovelScraper</a></p>" }
                    ?: "<p>Go back to NovelScraper; this tab can be closed.</p>") +
                "</div>"
        private const val PAGE_FAILED = "<!doctype html><meta charset=utf-8><title>NovelScraper</title>$PAGE_STYLE" +
            "<div><h2>Not signed in</h2><p>Go back to NovelScraper to try again.</p></div>"
    }
}

/**
 * A signed-in provider: hands out a current access token, refreshing it when
 * it is about to run out or the storage refuses it. [onRefreshChanged] saves a
 * rotated refresh token, which some providers issue on every refresh.
 */
internal class OAuthSession(
    private val client: OAuthClient,
    private var refreshToken: String,
    private val onRefreshChanged: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var current: OAuthTokens? = null

    /** Seed with tokens just obtained, so the first call needs no refresh. */
    fun seed(tokens: OAuthTokens) { current = tokens }

    suspend fun token(): String = lock.withLock {
        current?.takeIf { it.expiresAt - 60_000 > now() }?.let { return it.access }
        renew().access
    }

    /** The storage said the token is no good: get another, whatever its age. */
    suspend fun refreshNow(): String = lock.withLock { renew().access }

    private suspend fun renew(): OAuthTokens {
        val t = client.refresh(refreshToken)
        if (t.refresh != null && t.refresh != refreshToken) {
            refreshToken = t.refresh
            onRefreshChanged(t.refresh)
        }
        current = t
        return t
    }
}
