package com.novelscraper.app.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.security.MessageDigest
import java.util.Base64

/**
 * Google's token endpoint and the parts of the Drive API that
 * [GoogleDriveFolder] uses, in memory. Pages listings two files at a time, so
 * paging is always exercised, and checks every request's bearer token.
 */
class FakeGoogle : Dispatcher() {
    class File(val id: String, val name: String, var bytes: ByteArray, var modified: Int)

    val server = MockWebServer().also { it.dispatcher = this; it.start() }
    val base: String get() = server.url("/").toString().trimEnd('/')

    val files = LinkedHashMap<String, File>()
    private var nextId = 100
    private var clock = 0
    /** Access tokens Drive accepts now. */
    val validTokens = HashSet<String>()
    /** Refresh tokens the token endpoint honours. */
    val validRefresh = hashSetOf("rt-1")
    var tokensIssued = 0
    /** The PKCE challenge of the last sign-in page the test opened. */
    var challenge: String? = null
    val tokenRequests = ArrayList<Map<String, String>>()

    fun put(name: String, bytes: ByteArray): File =
        File("id${nextId++}", name, bytes, ++clock).also { files[it.id] = it }

    fun close() = server.shutdown()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        val path = url.encodedPath
        if (path == "/token") return token(request)
        val bearer = request.getHeader("Authorization")?.removePrefix("Bearer ")
        if (bearer !in validTokens) return json(401, """{"error":{"code":401}}""")
        val id = path.substringAfterLast('/')
        return when {
            request.method == "GET" && path == "/drive/v3/files" -> list(url)
            request.method == "GET" && path.startsWith("/drive/v3/files/") ->
                files[id]?.let { MockResponse().setBody(Buffer().write(it.bytes)) } ?: json(404, "{}")
            request.method == "PATCH" && path.startsWith("/upload/drive/v3/files/") ->
                files[id]?.let { it.bytes = request.body.readByteArray(); it.modified = ++clock; json(200, meta(it)) }
                    ?: json(404, "{}")
            request.method == "POST" && path == "/upload/drive/v3/files" -> create(request)
            request.method == "DELETE" -> if (files.remove(id) != null) MockResponse().setResponseCode(204) else json(404, "{}")
            else -> json(400, """{"error":"unexpected ${request.method} $path"}""")
        }
    }

    private fun list(url: HttpUrl): MockResponse {
        check(url.queryParameter("spaces") == "appDataFolder")
        val q = url.queryParameter("q")
        val named = q?.let { Regex("name = '(.*)'").find(it)?.groupValues?.get(1) }
        val all = files.values.filter { named == null || it.name == named }
        val start = url.queryParameter("pageToken")?.toInt() ?: 0
        val page = all.drop(start).take(2)
        val next = if (start + 2 < all.size) ""","nextPageToken":"${start + 2}"""" else ""
        return json(200, """{"files":[${page.joinToString(",") { meta(it) }}]$next}""")
    }

    private fun create(request: RecordedRequest): MockResponse {
        val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=").trim('"')
        val raw = request.body.readByteArray()
        val parts = split(raw, "--$boundary".toByteArray())
        val meta = Json.parseToJsonElement(String(parts[0])).jsonObject
        check(meta["parents"].toString() == """["appDataFolder"]""") { "created outside the app folder" }
        val f = put(meta["name"]!!.jsonPrimitive.content, parts[1])
        return json(200, meta(f))
    }

    /** The bodies of a multipart payload's parts, headers stripped. */
    private fun split(raw: ByteArray, delimiter: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = indexOf(raw, delimiter, 0)
        while (i >= 0) {
            val start = i + delimiter.size
            if (raw.size >= start + 2 && raw[start] == '-'.code.toByte() && raw[start + 1] == '-'.code.toByte()) break
            val next = indexOf(raw, delimiter, start)
            val part = raw.copyOfRange(start, if (next < 0) raw.size else next)
            val headerEnd = indexOf(part, "\r\n\r\n".toByteArray(), 0)
            // The body ends with the CRLF that precedes the next delimiter.
            out += part.copyOfRange(headerEnd + 4, part.size - 2)
            i = next
        }
        return out
    }

    private fun indexOf(a: ByteArray, b: ByteArray, from: Int): Int {
        outer@ for (i in from..a.size - b.size) {
            for (j in b.indices) if (a[i + j] != b[j]) continue@outer
            return i
        }
        return -1
    }

    private fun token(request: RecordedRequest): MockResponse {
        val form = request.body.readUtf8().split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }
        tokenRequests += form
        return when (form["grant_type"]) {
            "authorization_code" -> {
                if (form["code"] != "good-code") return json(400, """{"error":"invalid_grant"}""")
                val verifier = form["code_verifier"].orEmpty()
                val s256 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
                if (s256 != challenge) return json(400, """{"error":"invalid_grant","error_description":"PKCE"}""")
                issue(refresh = "rt-1")
            }
            "refresh_token" ->
                if (form["refresh_token"] in validRefresh) issue(refresh = null)
                else json(400, """{"error":"invalid_grant"}""")
            else -> json(400, """{"error":"unsupported_grant_type"}""")
        }
    }

    private fun issue(refresh: String?): MockResponse {
        val access = "at-${++tokensIssued}"
        validTokens += access
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"email":"reader@example.com"}""".toByteArray())
        val idToken = "e30.$claims.sig"
        val r = refresh?.let { ""","refresh_token":"$it"""" } ?: ""
        return json(200, """{"access_token":"$access","expires_in":3600,"id_token":"$idToken"$r}""")
    }

    private fun meta(f: File) =
        """{"id":"${f.id}","name":"${f.name}","size":"${f.bytes.size}","modifiedTime":"${java.time.Instant.ofEpochSecond(1_790_000_000L + f.modified)}"}"""

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
}
