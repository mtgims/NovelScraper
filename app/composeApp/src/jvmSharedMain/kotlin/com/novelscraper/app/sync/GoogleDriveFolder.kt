package com.novelscraper.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * A sync folder in Google Drive's app data folder: hidden from the person's
 * Drive, readable only by this app, and reached with the non-sensitive
 * `drive.appdata` scope, which sees nothing else in their Drive.
 *
 * Drive names are not unique: two creates of one name (a retry after a lost
 * answer, two devices writing the manifest at once) make two files. Every
 * device resolves a name the same way: the manifest by lowest id, which is what
 * [FolderSync] asks of storage, and anything else by the newest version.
 */
internal class GoogleDriveFolder(
    private val session: OAuthSession,
    client: OkHttpClient,
    private val base: String = "https://www.googleapis.com",
) : SyncFolder {

    private class DriveFile(val id: String, val name: String, val size: Long, val modified: String)

    private val http = client
    /** By name, as last listed or written; several when Drive holds duplicates. */
    private val known = HashMap<String, List<DriveFile>>()

    override suspend fun list(): List<RemoteFile> = io {
        val all = ArrayList<DriveFile>()
        var page: String? = null
        do {
            val url = "$base/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("fields", "nextPageToken,files(id,name,size,modifiedTime)")
                .addQueryParameter("pageSize", "1000")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val obj = call { Request.Builder().url(url).get() }.use { r -> check(r); parse(r) }
            obj["files"]?.jsonArray?.forEach { all += file(it.jsonObject) }
            page = obj.str("nextPageToken")
        } while (page != null)
        synchronized(known) {
            known.clear()
            all.groupBy { it.name }.forEach { (n, fs) -> known[n] = fs }
        }
        all.groupBy { it.name }.map { (n, fs) -> chosen(n, fs).let { RemoteFile(it.name, it.size, it.id) } }
    }

    override suspend fun read(name: String): ByteArray? = io {
        val f = resolve(name)?.let { chosen(name, it) } ?: return@io null
        call { Request.Builder().url("$base/drive/v3/files/${f.id}?alt=media").get() }.use { r ->
            if (r.code == 404) { forget(name); return@io null }
            check(r)
            r.body?.bytes() ?: ByteArray(0)
        }
    }

    override suspend fun write(name: String, bytes: ByteArray): Unit = io {
        val existing = resolve(name)?.let { chosen(name, it) }
        if (existing != null) {
            val updated = call {
                Request.Builder()
                    .url("$base/upload/drive/v3/files/${existing.id}?uploadType=media&fields=$FIELDS")
                    .patch(bytes.toRequestBody(OCTETS))
            }.use { r ->
                if (r.code == 404) null else { check(r); file(parse(r)) }
            }
            if (updated != null) { remember(updated, replacing = existing.id); return@io }
            forget(name)
        }
        val meta = """{"name":${Json.encodeToString(String.serializer(), name)},"parents":["appDataFolder"]}"""
        val body = MultipartBody.Builder().setType(RELATED)
            .addPart(meta.toRequestBody(JSON))
            .addPart(bytes.toRequestBody(OCTETS))
            .build()
        val created = call {
            Request.Builder().url("$base/upload/drive/v3/files?uploadType=multipart&fields=$FIELDS").post(body)
        }.use { r -> check(r); file(parse(r)) }
        remember(created, replacing = null)
    }

    override suspend fun delete(name: String): Unit = io {
        for (f in resolve(name).orEmpty()) {
            call { Request.Builder().url("$base/drive/v3/files/${f.id}").delete() }.use { r ->
                if (r.code != 404) check(r)
            }
        }
        forget(name)
    }

    // --- names to files --------------------------------------------------------------

    private fun chosen(name: String, files: List<DriveFile>): DriveFile =
        if (name == SyncFiles.MANIFEST) files.minBy { it.id }
        else files.maxWith(compareBy<DriveFile> { it.modified }.thenBy { it.id })

    /** The files of [name]: as last seen, else asked of Drive. */
    private suspend fun resolve(name: String): List<DriveFile>? {
        synchronized(known) { known[name] }?.let { return it }
        val q = "name = '${name.replace("\\", "\\\\").replace("'", "\\'")}' and trashed = false"
        val url = "$base/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files($FIELDS)")
            .build()
        val found = call { Request.Builder().url(url).get() }.use { r -> check(r); parse(r) }
            .let { o -> o["files"]?.jsonArray?.map { file(it.jsonObject) }.orEmpty() }
        if (found.isEmpty()) return null
        synchronized(known) { known[name] = found }
        return found
    }

    private fun remember(f: DriveFile, replacing: String?) = synchronized(known) {
        known[f.name] = known[f.name].orEmpty().filter { it.id != replacing && it.id != f.id } + f
    }

    private fun forget(name: String) = synchronized(known) { known.remove(name) }

    // --- HTTP ---------------------------------------------------------------------------

    /** One request with the current token; on a 401, once more with a new one. */
    private suspend fun call(build: () -> Request.Builder): Response {
        val first = http.newCall(build().header("Authorization", "Bearer ${session.token()}").build()).execute()
        if (first.code != 401) return first
        first.close()
        val again = http.newCall(build().header("Authorization", "Bearer ${session.refreshNow()}").build()).execute()
        if (again.code == 401) { again.close(); throw FolderAuthException("Google Drive refused the sign-in") }
        return again
    }

    private fun check(r: Response) {
        if (r.isSuccessful) return
        val text = r.peekBody(4096).string()
        when {
            r.code == 401 -> throw FolderAuthException("Google Drive refused the sign-in")
            // 403 is both "slow down" and "not allowed": only the latter needs a
            // new sign-in.
            r.code == 403 && ("RateLimit" in text || "rateLimit" in text) ->
                throw IOException("Google Drive asks to slow down")
            r.code == 403 && "storageQuotaExceeded" in text -> throw IOException("Google Drive is full")
            r.code == 403 -> throw FolderAuthException("Google Drive refused access")
            r.code == 429 -> throw IOException("Google Drive asks to slow down")
            else -> throw IOException("Google Drive answered ${r.code}")
        }
    }

    private fun parse(r: Response): JsonObject =
        runCatching { json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject }.getOrNull()
            ?: throw IOException("Google Drive answered with something that isn't JSON")

    private fun file(o: JsonObject) = DriveFile(
        id = o.str("id") ?: throw IOException("a Drive file without an id"),
        name = o.str("name").orEmpty(),
        size = o.str("size")?.toLongOrNull() ?: 0,
        modified = o.str("modifiedTime").orEmpty(),
    )

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val FIELDS = "id,name,size,modifiedTime"
        private val json = Json { ignoreUnknownKeys = true }
        private val OCTETS = "application/octet-stream".toMediaType()
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val RELATED = "multipart/related".toMediaType()

        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

        /** Google's sign-in for installed apps. The refresh token comes only with
         *  access_type=offline, and prompt=consent makes Google issue a new one
         *  even to someone who connected before. */
        fun oauth(http: OkHttpClient, base: String = "https://oauth2.googleapis.com",
                  authBase: String = "https://accounts.google.com") = OAuthClient(
            authUrl = "$authBase/o/oauth2/v2/auth",
            tokenUrl = "$base/token",
            clientId = SyncClients.GOOGLE_CLIENT_ID,
            clientSecret = SyncClients.GOOGLE_CLIENT_SECRET,
            scopes = listOf(SCOPE, "openid", "email"),
            authParams = mapOf("access_type" to "offline", "prompt" to "consent"),
            http = http,
        )
    }
}
