package com.novelscraper.app.epub

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What importing a file from somewhere else has to survive. */
class EpubRobustnessTest {

    private val realCap = Epub.maxTotalBytes
    @AfterTest fun restore() { Epub.maxTotalBytes = realCap }

    /** An EPUB whose single chapter is stored under [fileName] and referred to
     *  by [href], which is how a manifest escapes it. */
    private fun epub(fileName: String, href: String, body: String = "<p>Hello.</p>"): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
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
                        <dc:title>A Novel</dc:title><dc:creator>An Author</dc:creator>
                      </metadata>
                      <manifest><item id="c1" href="$href" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="c1"/></spine>
                    </package>""".trimIndent().toByteArray())
                put("OEBPS/$fileName", """<?xml version="1.0"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>C</title></head>
                    <body>$body</body></html>""".trimIndent().toByteArray())
            }
        }.toByteArray()

    /**
     * Hrefs are IRIs, so a space is written %20 while the zip stores the space
     * itself. Left encoded the lookup missed and the chapter disappeared from
     * the import without a word.
     */
    @Test fun aChapterWithASpaceInItsNameIsFound() {
        val parsed = Epub.read(ByteArrayInputStream(epub("Chapter 1.xhtml", "Chapter%201.xhtml")))
        assertEquals(1, parsed.chapters.size, "the chapter is found through its escaped href")
        assertTrue("Hello." in parsed.chapters.single().html)
    }

    @Test fun anUnescapedNameStillWorks() {
        val parsed = Epub.read(ByteArrayInputStream(epub("c1.xhtml", "c1.xhtml")))
        assertEquals(1, parsed.chapters.size)
    }

    /**
     * One entry claiming to be small can decompress to gigabytes. The budget is
     * spent during the read, so this refuses the file instead of running the
     * device out of memory first.
     */
    @Test fun anEntryBiggerThanTheBudgetIsRefused() {
        Epub.maxTotalBytes = 64 * 1024
        val fat = ByteArray(512 * 1024)   // compresses to nothing, expands past the cap
        val bomb = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("mimetype")); z.write("application/epub+zip".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("big.bin")); z.write(fat); z.closeEntry()
            }
        }.toByteArray()
        assertTrue(bomb.size < 64 * 1024, "the archive really is small: ${bomb.size} bytes")
        assertFailsWith<Epub.Invalid> { Epub.read(ByteArrayInputStream(bomb)) }
    }

    /**
     * Only five entity names are predefined in XML, so `&nbsp;` straight from a
     * source site makes an EPUB a strict reader will not open.
     */
    @Test fun writtenChaptersCarryNoUndeclaredEntities() {
        val chapter = Epub.Chapter("One", "<p>Spaced&nbsp;out&hellip; it&rsquo;s fine &amp; valid.</p>")
        val written = ByteArrayOutputStream()
            .also { Epub.write("A Novel", "An Author", listOf(chapter), it) }.toByteArray()

        var xhtml = ""
        ZipInputStream(ByteArrayInputStream(written)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.endsWith(".xhtml")) xhtml += z.readBytes().decodeToString()
            }
        }
        assertFalse("&nbsp;" in xhtml, "nbsp would make this unopenable in a strict reader")
        assertFalse("&hellip;" in xhtml)
        assertFalse("&rsquo;" in xhtml)
        assertTrue("&amp;" in xhtml, "a real ampersand is still escaped properly")
        assertTrue(" " in xhtml, "the non-breaking space survived as a character")
    }

    private fun importOne(body: String) = Epub.read(epub("c1.xhtml", "c1.xhtml", body).inputStream()).chapters.single()

    /** A tag ended at its first ">", so one inside a quoted attribute cut it short:
     *  the rest of the tag leaked into the text, and an image whose src came
     *  after it was lost. */
    @Test fun aGreaterThanSignInsideAnAttributeKeepsTheTagWhole() {
        val pic = epubWithImage("""<p>Before.</p><img alt="a > b" src="pic.png"/><p>After.</p>""")
        val c = Epub.read(pic.inputStream()).chapters.single()
        assertTrue("data:image/png" in c.html, "the image survived: ${c.html}")
        assertTrue("src=\"pic.png\"" !in c.html, "nothing of the tag leaked as text: ${c.html}")
        assertTrue("alt=\"a &gt; b\"" in c.html, "the alt text kept whole: ${c.html}")
    }

    @Test fun entitiesInATitleAreDecoded() {
        val c = importOne("""<h1>Don&#8217;t &hellip; caf&eacute; &#x2014; &Omega;</h1><p>Text.</p>""")
        assertEquals("Don\u2019t \u2026 caf\u00e9 \u2014 \u03a9", c.title)
    }

    /** An unclosed <svg> made the block remover scan to the end of the document
     *  once per opening, which for many of them is quadratic. */
    @Test fun manyUnclosedBlocksAreQuickAndLoseNoText() {
        val body = (1..20_000).joinToString("") { "<svg width=\"1\"><p>line $it</p>" }
        val started = System.currentTimeMillis()
        val c = importOne(body)
        val took = System.currentTimeMillis() - started
        assertTrue(took < 3_000, "took ${took}ms")
        assertTrue("line 1<" in c.html && "line 20000" in c.html, "every line kept")
    }

    /** One chapter showing pic.png, stored beside it. */
    private fun epubWithImage(body: String): ByteArray {
        val plain = epub("c1.xhtml", "c1.xhtml", body)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            java.util.zip.ZipInputStream(plain.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    z.putNextEntry(ZipEntry(e.name)); z.write(zin.readBytes()); z.closeEntry()
                }
            }
            z.putNextEntry(ZipEntry("OEBPS/pic.png")); z.write(ByteArray(64) { it.toByte() }); z.closeEntry()
        }
        return out.toByteArray()
    }
}
