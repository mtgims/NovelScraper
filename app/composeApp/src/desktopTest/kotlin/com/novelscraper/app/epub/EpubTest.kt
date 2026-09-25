package com.novelscraper.app.epub

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpubTest {

    private fun write(chapters: List<Epub.Chapter>, title: String = "A Novel", author: String = "An Author"): ByteArray =
        ByteArrayOutputStream().also { Epub.write(title, author, chapters, it) }.toByteArray()

    /** A minimal but valid EPUB holding one chapter of raw, unsanitised XHTML:
     *  what importing an untrusted file actually has to cope with. */
    private fun rawEpub(body: String, extra: Map<String, ByteArray> = emptyMap()): ByteArray =
        ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                fun put(name: String, bytes: ByteArray) {
                    z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
                }
                put("mimetype", "application/epub+zip".toByteArray())
                put("META-INF/container.xml", """<?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OEBPS/content.opf"
                        media-type="application/oebps-package+xml"/></rootfiles>
                    </container>""".trimIndent().toByteArray())
                put("OEBPS/content.opf", """<?xml version="1.0"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="i">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="i">x</dc:identifier>
                        <dc:title>Hostile</dc:title><dc:creator>Nobody</dc:creator>
                      </metadata>
                      <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="c1"/></spine>
                    </package>""".trimIndent().toByteArray())
                put("OEBPS/c1.xhtml", """<?xml version="1.0"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>C</title>
                    <style>head { display: none }</style></head><body>$body</body></html>""".trimIndent().toByteArray())
                for ((name, bytes) in extra) put(name, bytes)
            }
        }.toByteArray()

    @Test
    fun writtenThenReadKeepsTitlesAndText() {
        val chapters = listOf(
            Epub.Chapter("One", "<p>The first line.</p><p>The second.</p>"),
            Epub.Chapter("Two", "<p>Later on.</p>"),
        )
        val parsed = Epub.read(ByteArrayInputStream(write(chapters)))
        assertEquals("A Novel", parsed.title)
        assertEquals("An Author", parsed.author)
        assertEquals(listOf("One", "Two"), parsed.chapters.map { it.title })
        assertContains(parsed.chapters[0].html, "The first line.")
        assertContains(parsed.chapters[1].html, "Later on.")
    }

    @Test
    fun mimetypeIsTheFirstEntryAndStored() {
        // The EPUB spec requires it, and readers that check will refuse the file.
        ZipInputStream(ByteArrayInputStream(write(listOf(Epub.Chapter("A", "<p>x</p>"))))).use { zip ->
            val first: ZipEntry = zip.nextEntry!!
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertEquals("application/epub+zip", zip.readBytes().toString(Charsets.US_ASCII))
        }
    }

    @Test
    fun scriptsStylesAndHandlersAreStripped() {
        // Read straight from a file, not round-tripped: this is the untrusted path.
        val hostile = """
            <p onclick="steal()">Text <em>kept</em></p>
            <script>alert(document.cookie)</script>
            <style>p { color: red }</style>
            <a href="javascript:alert(1)">link</a>
            <iframe src="http://elsewhere/"></iframe>
        """.trimIndent()
        val html = Epub.read(ByteArrayInputStream(rawEpub(hostile))).chapters[0].html
        assertFalse("script" in html, html)
        assertFalse("javascript:" in html, html)
        assertFalse("onclick" in html, html)
        assertFalse("iframe" in html, html)
        assertFalse("alert" in html, html)
        assertFalse("color: red" in html, html)
        // The words survive; only the markup around them is dropped.
        assertContains(html, "Text")
        assertContains(html, "<em>kept</em>")
        assertContains(html, "link")
    }

    @Test
    fun anImageInsideTheFileIsInlined() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val html = Epub.read(ByteArrayInputStream(
            rawEpub("""<p>a</p><img src="pic.png" alt="a picture"/>""",
                    mapOf("OEBPS/pic.png" to png))
        )).chapters[0].html
        assertContains(html, "<img src=\"data:image/png;base64,")
        assertContains(html, "alt=\"a picture\"")
    }

    @Test
    fun anImageWithoutItsFileIsDroppedWhole() {
        // The reader maps placeholders to sources by position, so a surviving
        // <img> with no usable src would shift every later image in the chapter.
        val html = Epub.read(ByteArrayInputStream(write(listOf(
            Epub.Chapter("C", """<p>before</p><img src="missing.jpg" alt="gone"><p>after</p>""")
        )))).chapters[0].html
        assertFalse("<img" in html, html)
        assertContains(html, "before")
        assertContains(html, "after")
    }

    @Test
    fun chaptersWithNoWordsAreSkipped() {
        val parsed = Epub.read(ByteArrayInputStream(write(listOf(
            Epub.Chapter("Real", "<p>Words here.</p>"),
        ))))
        assertEquals(1, parsed.chapters.size)
        assertTrue(parsed.chapters.all { it.html.isNotBlank() })
    }

    @Test
    fun somethingThatIsNotAnEpubIsRefusedClearly() {
        assertFailsWith<Epub.Invalid> { Epub.read(ByteArrayInputStream("not a zip at all".toByteArray())) }
        // A zip that is not an EPUB: no container.xml.
        val plainZip = ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("hello.txt")); z.write("hi".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        assertFailsWith<Epub.Invalid> { Epub.read(ByteArrayInputStream(plainZip)) }
    }
}
