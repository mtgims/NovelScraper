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
}
