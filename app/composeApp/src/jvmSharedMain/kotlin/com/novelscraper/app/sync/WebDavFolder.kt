package com.novelscraper.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.IOException
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A sync folder on a WebDAV server: Nextcloud, ownCloud, Koofr, a NAS. The
 * library lives in a `NovelScraper` directory under the address given, made on
 * first use.
 *
 * Signs in with HTTP Basic on every request, so the address must be https,
 * except to a server on this machine or the local network, where plain http
 * is common and the password does not cross the internet.
 */
class WebDavFolder(
    address: String,
    private val user: String,
    private val password: String,
    client: OkHttpClient,
) : SyncFolder {

    /** The directory itself, always ending in '/'. */
    val url: HttpUrl = folderUrl(address)
    private val http = client.newBuilder().followRedirects(false).build()
    private var made = false

    override suspend fun list(): List<RemoteFile> = io {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop>""" +
            """<d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
        val req = request(url).method("PROPFIND", body.toRequestBody(XML))
            .header("Depth", "1").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) {
                // Not there yet: an empty folder, made now so writes can land.
                makeDirectory()
                return@io emptyList()
            }
            check(r)
            made = true
            parseListing(r.body?.string().orEmpty())
        }
    }

    override suspend fun read(name: String): ByteArray? = io {
        http.newCall(request(file(name)).get().build()).execute().use { r ->
            if (r.code == 404) return@io null
            check(r)
            r.body?.bytes() ?: ByteArray(0)
        }
    }

    override suspend fun write(name: String, bytes: ByteArray): Unit = io {
        if (!made) makeDirectory()
        http.newCall(request(file(name)).put(bytes.toRequestBody(OCTETS)).build()).execute().use(::check)
    }

    override suspend fun delete(name: String): Unit = io {
        http.newCall(request(file(name)).delete().build()).execute().use { r ->
            if (r.code != 404) check(r)
        }
    }

    /** MKCOL the directory; 405 means it is already there. */
    private fun makeDirectory() {
        http.newCall(request(url).method("MKCOL", null).build()).execute().use { r ->
            if (r.code != 405) check(r)
        }
        made = true
    }

    private fun file(name: String) = url.newBuilder().addPathSegment(name).build()

    private fun request(u: HttpUrl) = Request.Builder().url(u)
        .header("Authorization", Credentials.basic(user, password, Charsets.UTF_8))

    private fun check(r: Response) {
        when {
            r.isSuccessful -> {}
            r.code == 401 || r.code == 403 -> throw FolderAuthException("the WebDAV server refused the sign-in")
            r.code in 300..399 ->
                throw IOException("the WebDAV server redirects to ${r.header("Location")}; use that address")
            else -> throw IOException("the WebDAV server answered ${r.code}")
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val DIRECTORY = "NovelScraper"
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTETS = "application/octet-stream".toMediaType()

        /** [address] with the library's directory under it. Throws
         *  IllegalArgumentException for something that is not an http(s) URL. */
        fun folderUrl(address: String): HttpUrl {
            var a = address.trim()
            if (!a.startsWith("http://") && !a.startsWith("https://")) {
                require("://" !in a) { "Use an http or https address." }
                a = "https://$a"
            }
            val base = a.toHttpUrlOrNull() ?: throw IllegalArgumentException("not a web address")
            val b = base.newBuilder()
            // Already pointing at the directory (a second device given the full path).
            if (base.pathSegments.lastOrNull { it.isNotEmpty() } != DIRECTORY) b.addPathSegment(DIRECTORY)
            return b.addPathSegment("").build()
        }

        /**
         * The files in a PROPFIND multistatus (depth 1): the plain files in the directory,
         * not the directory itself and not subdirectories.
         *
         * Parsed defensively: Android's parser does not implement several of the
         * desktop JVM's settings (the EPUB importer learned this), so each is
         * tried, and DTDs are refused where the parser allows it.
         */
        internal fun parseListing(xml: String): List<RemoteFile> {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            val doc = f.newDocumentBuilder().parse(xml.byteInputStream())
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            val out = ArrayList<RemoteFile>()
            for (i in 0 until responses.length) {
                val r = responses.item(i) as Element
                val href = r.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: continue
                // The directory itself is listed too, as a collection.
                if (r.getElementsByTagNameNS("DAV:", "collection").length > 0) continue
                // href is a path or a full URL; either way the name is its last
                // segment, percent-encoded (and '+' is literal in a path).
                val name = URLDecoder.decode(href.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
                if (name.isEmpty()) continue
                val size = r.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0
                out += RemoteFile(name, size)
            }
            return out
        }
    }
}
