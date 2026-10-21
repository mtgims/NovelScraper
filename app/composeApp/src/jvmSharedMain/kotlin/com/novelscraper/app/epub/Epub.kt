package com.novelscraper.app.epub

import com.novelscraper.app.platform.Log
import org.w3c.dom.Element
import org.w3c.dom.Node
import com.novelscraper.app.net.epubFileName
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

    /** A hundred chapters of a novel, the unit an EPUB holds. */
    data class Volume(val number: Int, val chapters: List<Chapter>)

    /** How many chapters go in one volume, and so in one EPUB. The server split
     *  scraped novels the same way, so a novel exported now matches the files
     *  exported from it before. */
    const val CHAPTERS_PER_VOLUME = 100

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
    /** What all of a book's inlined images may add up to, counting each copy (see [ImageBudget]). */
    internal const val MAX_BOOK_IMAGE_BYTES = 32L * 1024 * 1024

    /** Guards against a zip bomb: an EPUB of novel text is far under this. */
    /** Not const: a test lowers it, rather than building a quarter-gigabyte
     *  fixture to prove the budget is enforced. */
    internal var maxTotalBytes = 256L * 1024 * 1024

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
        val images = ImageBudget()
        for (ref in pkg.elements("spine").flatMap { it.elements("itemref") }) {
            if (ref.getAttribute("linear") == "no") continue
            val item = manifest[ref.getAttribute("idref")] ?: continue
            if ("nav" in item.props) continue          // the table of contents is not a chapter
            val path = resolve(base, item.href)
            val raw = files[path]?.toString(Charsets.UTF_8) ?: continue
            val body = bodyOf(raw)
            val html = sanitize(body, files, path.substringBeforeLast('/', ""), images)
            if (plainIsEmpty(html)) continue           // a cover page or a blank divider
            chapters.add(Chapter(headingOf(body) ?: "Chapter ${chapters.size + 1}", html))
        }
        if (chapters.isEmpty()) throw Invalid("no readable chapters")
        return Parsed(title, author, cover, chapters)
    }

    /** Read one zip entry, refusing it as soon as it passes [budget] bytes. */
    private fun readWithin(zip: ZipInputStream, budget: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var left = budget
        while (true) {
            val n = zip.read(chunk)
            if (n <= 0) break
            left -= n
            if (left < 0) throw Invalid("the file is too large")
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                // Read against the remaining budget rather than reading the
                // entry and measuring afterwards. One entry claiming to be a
                // few KB can decompress to gigabytes, and readBytes() would
                // have taken the app down before the check below ever ran.
                val bytes = readWithin(zip, maxTotalBytes - total)
                total += bytes.size
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

    /**
     * Undo percent-escaping in a manifest href.
     *
     * Hrefs are IRIs, so a file called "Chapter 1.xhtml" is written
     * "Chapter%201.xhtml", while the zip stores the name with the space. Left
     * encoded, the lookup missed and the chapter silently vanished from the
     * import. Deliberately not URLDecoder, which also turns "+" into a space,
     * and a plus is a perfectly ordinary character in a filename.
     */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                out.write(hex)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    /** A manifest href is relative to the package file. */
    private fun resolve(base: String, href: String): String {
        val clean = percentDecode(href.substringBefore('#').removePrefix("./"))
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

    /**
     * The images one book inlines. Each chapter keeps its own copy of an image,
     * as a data URL in its stored text, so an ornament shown in every chapter
     * was stored once per chapter: a 1 MiB one in 100 chapters was 140 MB.
     * Each image is now encoded once, and the copies are counted against
     * [MAX_BOOK_IMAGE_BYTES]; past that, an image is dropped as an oversized
     * one is.
     */
    private class ImageBudget {
        private val encoded = HashMap<String, String?>()
        private var spent = 0L

        fun url(path: String, files: Map<String, ByteArray>, mediaType: String): String? {
            val url = encoded.getOrPut(path) { dataUrl(files[path], mediaType) } ?: return null
            if (spent + url.length > MAX_BOOK_IMAGE_BYTES) return null
            spent += url.length
            return url
        }
    }

    private fun dataUrl(bytes: ByteArray?, mediaType: String): String? {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val type = mediaType.ifBlank { "image/jpeg" }
        return "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    // --- markup ---------------------------------------------------------------

    private val BODY = Regex("<body[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HEADING = Regex("<h[1-6][^>]*>(.*?)</h[1-6]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    /** Blocks dropped whole, contents and all ([dropBlocks]). */
    private val DROP_OPEN = Regex("<(script|style|head|svg)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
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
    private fun sanitize(body: String, files: Map<String, ByteArray>, dir: String, images: ImageBudget): String {
        var s = dropBlocks(body)
        s = COMMENT.replace(s, "")
        return replaceTags(s) { closing, name, attrs ->
            when {
                name !in KEEP -> ""
                name == "img" -> {
                    if (closing) "" else {
                        val src = SRC.find(attrs)?.groupValues?.get(1).orEmpty()
                        val alt = ALT.find(attrs)?.groupValues?.get(1).orEmpty()
                        val url = src.takeIf { it.isNotBlank() }
                            ?.let { images.url(resolve(dir, it), files, mediaTypeOf(src)) }
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

    /**
     * Text with its character references decoded, in one pass (so "&amp;lt;"
     * stays "&lt;"): decimal, hexadecimal and every HTML 4 name. It knew six
     * names, so a title kept "&#8217;" and "&hellip;" as written. A non-breaking
     * space becomes a plain one, as titles and the empty-page check want.
     */
    private fun unescape(s: String): String = ENTITY.replace(s) { m ->
        val ref = m.groupValues[1]
        val code = when {
            ref.startsWith("#x") || ref.startsWith("#X") -> ref.drop(2).toIntOrNull(16)
            ref.startsWith("#") -> ref.drop(1).toIntOrNull()
            else -> null
        }
        when {
            code != null && Character.isValidCodePoint(code) && code != 0 -> String(Character.toChars(code))
            ref.startsWith("#") -> m.value
            else -> HTML_ENTITIES[ref] ?: m.value
        }
    }.replace('\u00A0', ' ')

    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});")

    /**
     * Each tag in [html] replaced by what [transform] makes of it (closing?,
     * lower-case name, attributes). A tag ends at the first ">" outside quotes:
     * a regex ending it at the first ">" anywhere cut `alt="a > b"` short,
     * leaking the rest of the tag as text and losing a src that came after it.
     * A quote never closed falls back to the first ">", as before. One pass.
     */
    private fun replaceTags(html: String, transform: (Boolean, String, String) -> String): String {
        val out = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) { out.append(html, i, html.length); break }
            out.append(html, i, lt)
            var j = lt + 1
            val closing = j < html.length && html[j] == '/'
            if (closing) j++
            val nameStart = j
            while (j < html.length && html[j].isLetterOrDigit()) j++
            val name = html.substring(nameStart, j)
            // Not a tag ("<" in text, "<!", "< b"): kept as it is.
            if (name.isEmpty() || (j < html.length && !html[j].isWhitespace() && html[j] != '>' && html[j] != '/')) {
                out.append('<'); i = lt + 1; continue
            }
            var end = -1
            var quote = 0.toChar()
            var k = j
            while (k < html.length) {
                val c = html[k]
                if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar() }
                else if (c == '"' || c == '\'') quote = c
                else if (c == '>') { end = k; break }
                k++
            }
            if (end < 0) end = html.indexOf('>', j)          // an unclosed quote: as before
            if (end < 0) { out.append(html, lt, html.length); break }   // never closed: text
            val attrs = html.substring(j, end).removeSuffix("/")
            out.append(transform(closing, name.lowercase(), attrs))
            i = end + 1
        }
        return out.toString()
    }

    /**
     * [html] without its script, style, head and svg blocks, contents and all.
     * A block never closed loses only its opening tag. One pass: the regex
     * this replaces looked for the closing tag from every opening one, to the
     * end of the document each time one was missing, so 20,000 unclosed ones
     * took 36 s.
     */
    private fun dropBlocks(html: String): String {
        val out = StringBuilder(html.length)
        // Where looking for a closing tag already found none, by name: none
        // will be found from anywhere after there either.
        val noCloseFrom = HashMap<String, Int>()
        var i = 0
        while (true) {
            val open = DROP_OPEN.find(html, i) ?: break
            out.append(html, i, open.range.first)
            val name = open.groupValues[1].lowercase()
            val after = open.range.last + 1
            val known = noCloseFrom[name]
            val close = if (known != null && after >= known) -1 else html.indexOf("</$name", after, ignoreCase = true)
            if (close < 0) {
                noCloseFrom[name] = minOf(after, known ?: after)
                i = after                                    // only the opening tag goes
                continue
            }
            val gt = html.indexOf('>', close)
            i = if (gt < 0) html.length else gt + 1
        }
        if (i < html.length) out.append(html, i, html.length)
        return out.toString()
    }

    private fun xml(bytes: ByteArray) = try {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false      // EPUBs vary wildly in how they declare namespaces
            // An imported file is untrusted: no doctype, no external entities.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            // Android's parser does not implement these two and the abstract base
            // class throws UnsupportedOperationException for them, which is why
            // this has to tolerate failure the same way the features above do.
            // Desktop JVM implements them, so only a device catches it.
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (e: Exception) {
        // The reason matters: every EPUB that fails to import fails here, and
        // "not readable" alone says nothing about which part gave up.
        Log.w("Epub", "xml parse: ${e::class.simpleName}: ${e.message}")
        throw Invalid("the package file is not readable (${e.message})")
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

    /**
     * Several volumes as one zip of EPUBs, one file per volume, named the way
     * the server named them. A novel past a hundred chapters is too big to hand
     * over as a single file, and volume-sized pieces are what a reader expects.
     */
    /**
     * Several volumes as a zip of EPUBs. Each volume is asked for ([volume]) as
     * it is written and let go after, so only one is ever held.
     */
    suspend fun writeZip(
        slug: String,
        title: String,
        author: String,
        numbers: List<Int>,
        volume: suspend (Int) -> Volume,
        out: OutputStream,
    ) {
        ZipOutputStream(out).use { zip ->
            for (n in numbers) {
                val v = volume(n)
                zip.putNextEntry(ZipEntry(epubFileName(slug, v.number)))
                // The volume's own title, so the file says which part it holds.
                ByteArrayOutputStream().also { buf ->
                    write("$title - Volume ${v.number}", author, v.chapters, buf)
                }.let { zip.write(it.toByteArray()) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Named entities XHTML has no declaration for, resolved to the characters
     * they mean.
     *
     * Only five entity names are predefined in XML. Everything else needs a
     * DTD, so `&nbsp;` straight from a source site, which is most of them, is
     * not well-formed in an EPUB and a strict reader refuses the file. The
     * output is UTF-8, so the character itself is always safe.
     *
     * Every HTML 4 name is known (it was 31). One not listed is neutralised to
     * a literal "&name;" instead of being dropped: it reads oddly in the rare
     * case, where silently losing text or producing a file that will not open
     * reads worse.
     */
    private val HTML_ENTITIES: Map<String, String> = mapOf(
        "AElig" to "\u00C6", "Aacute" to "\u00C1", "Acirc" to "\u00C2", "Agrave" to "\u00C0",
        "Alpha" to "\u0391", "Aring" to "\u00C5", "Atilde" to "\u00C3", "Auml" to "\u00C4",
        "Beta" to "\u0392", "Ccedil" to "\u00C7", "Chi" to "\u03A7", "Dagger" to "\u2021",
        "Delta" to "\u0394", "ETH" to "\u00D0", "Eacute" to "\u00C9", "Ecirc" to "\u00CA",
        "Egrave" to "\u00C8", "Epsilon" to "\u0395", "Eta" to "\u0397", "Euml" to "\u00CB",
        "Gamma" to "\u0393", "Iacute" to "\u00CD", "Icirc" to "\u00CE", "Igrave" to "\u00CC",
        "Iota" to "\u0399", "Iuml" to "\u00CF", "Kappa" to "\u039A", "Lambda" to "\u039B", "Mu" to "\u039C",
        "Ntilde" to "\u00D1", "Nu" to "\u039D", "OElig" to "\u0152", "Oacute" to "\u00D3",
        "Ocirc" to "\u00D4", "Ograve" to "\u00D2", "Omega" to "\u03A9", "Omicron" to "\u039F",
        "Oslash" to "\u00D8", "Otilde" to "\u00D5", "Ouml" to "\u00D6", "Phi" to "\u03A6", "Pi" to "\u03A0",
        "Prime" to "\u2033", "Psi" to "\u03A8", "Rho" to "\u03A1", "Scaron" to "\u0160", "Sigma" to "\u03A3",
        "THORN" to "\u00DE", "Tau" to "\u03A4", "Theta" to "\u0398", "Uacute" to "\u00DA",
        "Ucirc" to "\u00DB", "Ugrave" to "\u00D9", "Upsilon" to "\u03A5", "Uuml" to "\u00DC",
        "Xi" to "\u039E", "Yacute" to "\u00DD", "Yuml" to "\u0178", "Zeta" to "\u0396", "aacute" to "\u00E1",
        "acirc" to "\u00E2", "acute" to "\u00B4", "aelig" to "\u00E6", "agrave" to "\u00E0",
        "alefsym" to "\u2135", "alpha" to "\u03B1", "amp" to "\u0026", "and" to "\u2227", "ang" to "\u2220",
        "aring" to "\u00E5", "asymp" to "\u2248", "atilde" to "\u00E3", "auml" to "\u00E4",
        "bdquo" to "\u201E", "beta" to "\u03B2", "brvbar" to "\u00A6", "bull" to "\u2022", "cap" to "\u2229",
        "ccedil" to "\u00E7", "cedil" to "\u00B8", "cent" to "\u00A2", "chi" to "\u03C7", "circ" to "\u02C6",
        "clubs" to "\u2663", "cong" to "\u2245", "copy" to "\u00A9", "crarr" to "\u21B5", "cup" to "\u222A",
        "curren" to "\u00A4", "dArr" to "\u21D3", "dagger" to "\u2020", "darr" to "\u2193",
        "deg" to "\u00B0", "delta" to "\u03B4", "diams" to "\u2666", "divide" to "\u00F7",
        "eacute" to "\u00E9", "ecirc" to "\u00EA", "egrave" to "\u00E8", "empty" to "\u2205",
        "emsp" to "\u2003", "ensp" to "\u2002", "epsilon" to "\u03B5", "equiv" to "\u2261",
        "eta" to "\u03B7", "eth" to "\u00F0", "euml" to "\u00EB", "euro" to "\u20AC", "exist" to "\u2203",
        "fnof" to "\u0192", "forall" to "\u2200", "frac12" to "\u00BD", "frac14" to "\u00BC",
        "frac34" to "\u00BE", "frasl" to "\u2044", "gamma" to "\u03B3", "ge" to "\u2265", "gt" to "\u003E",
        "hArr" to "\u21D4", "harr" to "\u2194", "hearts" to "\u2665", "hellip" to "\u2026",
        "iacute" to "\u00ED", "icirc" to "\u00EE", "iexcl" to "\u00A1", "igrave" to "\u00EC",
        "image" to "\u2111", "infin" to "\u221E", "int" to "\u222B", "iota" to "\u03B9",
        "iquest" to "\u00BF", "isin" to "\u2208", "iuml" to "\u00EF", "kappa" to "\u03BA",
        "lArr" to "\u21D0", "lambda" to "\u03BB", "lang" to "\u2329", "laquo" to "\u00AB",
        "larr" to "\u2190", "lceil" to "\u2308", "ldquo" to "\u201C", "le" to "\u2264", "lfloor" to "\u230A",
        "lowast" to "\u2217", "loz" to "\u25CA", "lrm" to "\u200E", "lsaquo" to "\u2039",
        "lsquo" to "\u2018", "lt" to "\u003C", "macr" to "\u00AF", "mdash" to "\u2014", "micro" to "\u00B5",
        "middot" to "\u00B7", "minus" to "\u2212", "mu" to "\u03BC", "nabla" to "\u2207", "nbsp" to "\u00A0",
        "ndash" to "\u2013", "ne" to "\u2260", "ni" to "\u220B", "not" to "\u00AC", "notin" to "\u2209",
        "nsub" to "\u2284", "ntilde" to "\u00F1", "nu" to "\u03BD", "oacute" to "\u00F3",
        "ocirc" to "\u00F4", "oelig" to "\u0153", "ograve" to "\u00F2", "oline" to "\u203E",
        "omega" to "\u03C9", "omicron" to "\u03BF", "oplus" to "\u2295", "or" to "\u2228",
        "ordf" to "\u00AA", "ordm" to "\u00BA", "oslash" to "\u00F8", "otilde" to "\u00F5",
        "otimes" to "\u2297", "ouml" to "\u00F6", "para" to "\u00B6", "part" to "\u2202",
        "permil" to "\u2030", "perp" to "\u22A5", "phi" to "\u03C6", "pi" to "\u03C0", "piv" to "\u03D6",
        "plusmn" to "\u00B1", "pound" to "\u00A3", "prime" to "\u2032", "prod" to "\u220F",
        "prop" to "\u221D", "psi" to "\u03C8", "quot" to "\u0022", "rArr" to "\u21D2", "radic" to "\u221A",
        "rang" to "\u232A", "raquo" to "\u00BB", "rarr" to "\u2192", "rceil" to "\u2309",
        "rdquo" to "\u201D", "real" to "\u211C", "reg" to "\u00AE", "rfloor" to "\u230B", "rho" to "\u03C1",
        "rlm" to "\u200F", "rsaquo" to "\u203A", "rsquo" to "\u2019", "sbquo" to "\u201A",
        "scaron" to "\u0161", "sdot" to "\u22C5", "sect" to "\u00A7", "shy" to "\u00AD", "sigma" to "\u03C3",
        "sigmaf" to "\u03C2", "sim" to "\u223C", "spades" to "\u2660", "sub" to "\u2282", "sube" to "\u2286",
        "sum" to "\u2211", "sup" to "\u2283", "sup1" to "\u00B9", "sup2" to "\u00B2", "sup3" to "\u00B3",
        "supe" to "\u2287", "szlig" to "\u00DF", "tau" to "\u03C4", "there4" to "\u2234",
        "theta" to "\u03B8", "thetasym" to "\u03D1", "thinsp" to "\u2009", "thorn" to "\u00FE",
        "tilde" to "\u02DC", "times" to "\u00D7", "trade" to "\u2122", "uArr" to "\u21D1",
        "uacute" to "\u00FA", "uarr" to "\u2191", "ucirc" to "\u00FB", "ugrave" to "\u00F9",
        "uml" to "\u00A8", "upsih" to "\u03D2", "upsilon" to "\u03C5", "uuml" to "\u00FC",
        "weierp" to "\u2118", "xi" to "\u03BE", "yacute" to "\u00FD", "yen" to "\u00A5", "yuml" to "\u00FF",
        "zeta" to "\u03B6", "zwj" to "\u200D", "zwnj" to "\u200C",
    )
    private val XML_PREDEFINED = setOf("amp", "lt", "gt", "quot", "apos")
    private val ENTITY_OR_AMP = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]{1,31});|&")

    private fun xmlEntities(s: String): String = ENTITY_OR_AMP.replace(s) { m ->
        val ref = m.groupValues[1]
        when {
            ref.isEmpty() -> "&amp;"            // a bare ampersand in the text
            ref.startsWith("#") -> m.value      // numeric, always valid
            ref in XML_PREDEFINED -> m.value
            else -> HTML_ENTITIES[ref] ?: "&amp;$ref;"
        }
    }

    /** Stored chapter HTML is a fragment, and not necessarily well-formed XML.
     *  Close the void tags XHTML insists on and drop anything unrecognised. */
    private fun xhtmlBody(html: String): String =
        replaceTags(xmlEntities(html)) { closing, name, attrs ->
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
