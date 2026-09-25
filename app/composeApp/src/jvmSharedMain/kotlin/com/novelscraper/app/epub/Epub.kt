package com.novelscraper.app.epub

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reading and writing EPUB files on the device.
 *
 * Importing used to be the server's job; it is done here now, so an imported
 * novel needs no account and never leaves the device (it is deliberately not
 * synced: the records carry metadata, not books).
 *
 * An imported document is untrusted, so its markup is reduced to the handful of
 * tags the reader understands and every script, style, handler and non-http URL
 * is dropped. What comes out is the same shape of HTML a source extension
 * returns, which matters because the reader, the highlighting and narration all
 * index the flattened text of it (see [com.novelscraper.app.data.Sentences]).
 */
object Epub {

    /** One spine document: a chapter in the reader. */
    data class Chapter(val title: String, val html: String)

    data class Parsed(
        val title: String,
        val author: String,
        /** The cover as a data URL, or null. */
        val cover: String?,
        val chapters: List<Chapter>,
    )

    class Invalid(message: String) : Exception(message)

    /** Images are inlined as data URLs so an imported novel is one row in the
     *  database with no files to track or clean up. Anything larger than this is
     *  dropped rather than bloating the library. */
    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

    /** Guards against a zip bomb: an EPUB of novel text is far under this. */
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    // --- reading -------------------------------------------------------------

    fun read(input: InputStream): Parsed {
        val files = unzip(input)
        val opfPath = rootfilePath(files)
        val opf = files[opfPath] ?: throw Invalid("the package file is missing")
        val base = opfPath.substringBeforeLast('/', "")

        val doc = xml(opf)
        val pkg = doc.documentElement ?: throw Invalid("the package file is empty")

        val title = meta(pkg, "title").ifBlank { "Untitled" }
        val author = meta(pkg, "creator")

        // manifest: id -> (href, media-type, properties)
        data class Item(val href: String, val type: String, val props: String)
        val manifest = HashMap<String, Item>()
        for (el in pkg.elements("manifest").flatMap { it.elements("item") }) {
            val id = el.getAttribute("id")
            if (id.isNotEmpty()) manifest[id] = Item(
                el.getAttribute("href"), el.getAttribute("media-type"), el.getAttribute("properties"))
        }

        val coverId = pkg.elements("metadata").flatMap { it.elements("meta") }
            .firstOrNull { it.getAttribute("name") == "cover" }?.getAttribute("content")
        val coverItem = manifest[coverId] ?: manifest.values.firstOrNull { "cover-image" in it.props }
        val cover = coverItem?.let { dataUrl(files[resolve(base, it.href)], it.type) }

        val chapters = ArrayList<Chapter>()
        for (ref in pkg.elements("spine").flatMap { it.elements("itemref") }) {
            if (ref.getAttribute("linear") == "no") continue
            val item = manifest[ref.getAttribute("idref")] ?: continue
            if ("nav" in item.props) continue          // the table of contents is not a chapter
            val path = resolve(base, item.href)
            val raw = files[path]?.toString(Charsets.UTF_8) ?: continue
            val body = bodyOf(raw)
            val html = sanitize(body, files, path.substringBeforeLast('/', ""))
            if (plainIsEmpty(html)) continue           // a cover page or a blank divider
            chapters.add(Chapter(headingOf(body) ?: "Chapter ${chapters.size + 1}", html))
        }
        if (chapters.isEmpty()) throw Invalid("no readable chapters")
        return Parsed(title, author, cover, chapters)
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val bytes = zip.readBytes()
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) throw Invalid("the file is too large")
                // Normalise away "./" and any parent traversal in the stored name.
                out[entry.name.removePrefix("./").replace("\\", "/")] = bytes
            }
        }
        if (out.isEmpty()) throw Invalid("not a zip file")
        return out
    }

    private fun rootfilePath(files: Map<String, ByteArray>): String {
        val container = files["META-INF/container.xml"] ?: throw Invalid("not an EPUB (no container.xml)")
        val path = xml(container).documentElement.elements("rootfiles")
            .flatMap { it.elements("rootfile") }
            .firstOrNull()?.getAttribute("full-path")
        return path?.takeIf { it.isNotBlank() } ?: throw Invalid("not an EPUB (no package file)")
    }

    /** A manifest href is relative to the package file. */
    private fun resolve(base: String, href: String): String {
        val clean = href.substringBefore('#').removePrefix("./")
        if (base.isEmpty()) return clean
        val parts = ArrayList<String>()
        for (seg in (base.split('/') + clean.split('/'))) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun dataUrl(bytes: ByteArray?, mediaType: String): String? {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val type = mediaType.ifBlank { "image/jpeg" }
        return "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    // --- markup ---------------------------------------------------------------

    private val BODY = Regex("<body[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HEADING = Regex("<h[1-6][^>]*>(.*?)</h[1-6]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val DROP_BLOCK = Regex("<(script|style|head|svg)\\b[^>]*>.*?</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<(/?)([a-zA-Z0-9]+)((?:\\s+[^>]*)?)/?>", RegexOption.DOT_MATCHES_ALL)
    private val SRC = Regex("\\bsrc\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val ALT = Regex("\\balt\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

    /** The tags the reader's flattening understands. Everything else is unwrapped
     *  (its text is kept, its markup dropped). */
    private val KEEP = setOf("p", "br", "em", "i", "strong", "b", "u", "blockquote",
        "h1", "h2", "h3", "h4", "h5", "h6", "hr", "img", "ul", "ol", "li")

    private fun bodyOf(raw: String): String = BODY.find(raw)?.groupValues?.get(1) ?: raw

    private fun headingOf(body: String): String? =
        HEADING.find(body)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]*>"), "")?.let { unescape(it) }?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Reduce a spine document to the tags in [KEEP], resolving each image to a
     * data URL from inside the EPUB. Scripts, styles, handlers, and links to
     * anywhere are gone: nothing here is fetched and nothing is executed.
     */
    private fun sanitize(body: String, files: Map<String, ByteArray>, dir: String): String {
        var s = DROP_BLOCK.replace(body, "")
        s = COMMENT.replace(s, "")
        return TAG.replace(s) { m ->
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            when {
                name !in KEEP -> ""
                name == "img" -> {
                    if (closing) "" else {
                        val src = SRC.find(attrs)?.groupValues?.get(1).orEmpty()
                        val alt = ALT.find(attrs)?.groupValues?.get(1).orEmpty()
                        val url = src.takeIf { it.isNotBlank() }
                            ?.let { files[resolve(dir, it)] }
                            ?.let { dataUrl(it, mediaTypeOf(src)) }
                        // An image that cannot be shown is dropped whole: the
                        // reader maps placeholders to sources by position, so a
                        // srcless <img> would shift every later image.
                        if (url == null) "" else "<img src=\"$url\" alt=\"${escape(alt)}\">"
                    }
                }
                // Everything kept is emitted bare: no class, style, id or handler.
                else -> if (closing) "</$name>" else "<$name>"
            }
        }
    }

    private fun mediaTypeOf(href: String) = when (href.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "image/jpeg"
    }

    /** True when the document holds no words and no image (a cover page). */
    private fun plainIsEmpty(html: String): Boolean =
        html.replace(Regex("<img[^>]*>"), "\uFFFC")
            .replace(Regex("<[^>]*>"), " ")
            .let { unescape(it) }.isBlank()

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescape(s: String) = s
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    private fun xml(bytes: ByteArray) = try {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false      // EPUBs vary wildly in how they declare namespaces
            // An imported file is untrusted: no doctype, no external entities.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isXIncludeAware = false
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (e: Exception) {
        throw Invalid("the package file is not readable")
    }

    /** Child elements by local name, ignoring any namespace prefix. */
    private fun Element.elements(name: String): List<Element> {
        val out = ArrayList<Element>()
        val kids = childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) {
                val el = n as Element
                if (el.tagName.substringAfter(':').equals(name, ignoreCase = true)) out.add(el)
                else if (el.tagName.substringAfter(':').equals("metadata", ignoreCase = true) && name == "meta")
                    out.addAll(el.elements("meta"))
            }
        }
        return out
    }

    private fun meta(pkg: Element, name: String): String =
        pkg.elements("metadata").flatMap { it.elements(name) }
            .firstOrNull()?.textContent?.trim().orEmpty()

    // --- writing ---------------------------------------------------------------

    /**
     * Write [chapters] as an EPUB 3 file. Used by "Save as EPUB", which the
     * server used to build; it works offline now, for any novel whose chapters
     * are on the device.
     */
    fun write(title: String, author: String, chapters: List<Chapter>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            // "mimetype" must come first and be stored, not deflated.
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()

            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            put("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""")

            chapters.forEachIndexed { i, c ->
                put("OEBPS/ch${i + 1}.xhtml", """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${escape(c.title)}</title></head>
<body><h1>${escape(c.title)}</h1>
${xhtmlBody(c.html)}
</body></html>""")
            }

            val manifest = chapters.indices.joinToString("\n    ") {
                """<item id="ch${it + 1}" href="ch${it + 1}.xhtml" media-type="application/xhtml+xml"/>"""
            }
            val spine = chapters.indices.joinToString("\n    ") { """<itemref idref="ch${it + 1}"/>""" }
            put("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">novelscraper:${title.hashCode().toUInt()}</dc:identifier>
    <dc:title>${escape(title)}</dc:title>
    <dc:creator>${escape(author.ifBlank { "Unknown" })}</dc:creator>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">1970-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    $manifest
  </manifest>
  <spine>
    $spine
  </spine>
</package>""")

            val toc = chapters.mapIndexed { i, c ->
                """<li><a href="ch${i + 1}.xhtml">${escape(c.title)}</a></li>"""
            }.joinToString("\n      ")
            put("OEBPS/nav.xhtml", """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>${escape(title)}</title></head>
<body><nav epub:type="toc"><h1>Contents</h1><ol>
      $toc
</ol></nav></body></html>""")
        }
    }

    /** Stored chapter HTML is a fragment, and not necessarily well-formed XML.
     *  Close the void tags XHTML insists on and drop anything unrecognised. */
    private fun xhtmlBody(html: String): String =
        TAG.replace(html) { m ->
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            when {
                name !in KEEP -> ""
                name == "br" || name == "hr" -> "<$name/>"
                name == "img" -> if (closing) "" else {
                    val src = SRC.find(attrs)?.groupValues?.get(1).orEmpty()
                    val alt = ALT.find(attrs)?.groupValues?.get(1).orEmpty()
                    if (src.isBlank()) "" else """<img src="${escape(src)}" alt="${escape(alt)}"/>"""
                }
                else -> if (closing) "</$name>" else "<$name>"
            }
        }
}
