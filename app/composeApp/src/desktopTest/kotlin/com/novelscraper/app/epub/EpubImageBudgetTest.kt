package com.novelscraper.app.epub

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Images are inlined into each chapter that shows them, so a book has a budget. */
class EpubImageBudgetTest {

    /** A book of [chapters] chapters, each showing the same [imageBytes]-byte image. */
    private fun book(chapters: Int, imageBytes: Int): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("mimetype", "application/epub+zip".toByteArray())
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            val items = (1..chapters).joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" }
            val spine = (1..chapters).joinToString("") { """<itemref idref="c$it"/>""" }
            put("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Pictures</dc:title></metadata><manifest>$items<item id="img" href="ornament.jpg" media-type="image/jpeg"/></manifest><spine>$spine</spine></package>""".toByteArray())
            for (i in 1..chapters) {
                put("OEBPS/c$i.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Chapter $i</h1><img src="ornament.jpg" alt="*"/><p>Text $i.</p></body></html>""".toByteArray())
            }
            put("OEBPS/ornament.jpg", ByteArray(imageBytes) { (it % 251).toByte() })
        }
    }.toByteArray()

    @Test fun anImageRepeatedInEveryChapterStaysWithinTheBooksBudget() {
        // 1 MiB shown in 100 chapters: 100 copies of it, inlined, were ~133 MB.
        val parsed = Epub.read(book(100, 1024 * 1024).inputStream())
        assertEquals(100, parsed.chapters.size, "every chapter is still there")
        val inlined = parsed.chapters.sumOf { c -> Regex("data:[^\"]+").findAll(c.html).sumOf { it.value.length.toLong() } }
        assertTrue(inlined <= Epub.MAX_BOOK_IMAGE_BYTES, "inlined images total $inlined bytes")
        assertTrue("data:image/jpeg" in parsed.chapters.first().html, "the first chapters keep their image")
        assertTrue(parsed.chapters.all { "Text" in it.html }, "no chapter loses its text")
    }
}
