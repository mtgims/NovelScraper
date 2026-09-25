package com.novelscraper.app.net

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsTest {
    @Test fun fileNameRule() {
        // One volume of a long novel, a short novel whole, and a zip of volumes.
        assertEquals("renegade-immortal-volume-3.epub", epubFileName("renegade-immortal", 3))
        assertEquals("a_b_c.epub", epubFileName("a b/c"))
        assertEquals("book.epub", epubFileName("???"))
        assertEquals("renegade-immortal.zip", zipFileName("renegade-immortal"))
        assertEquals("a_b_c.zip", zipFileName("a b/c"))
        assertEquals("book.zip", zipFileName("???"))
    }

    @Test fun neverOverwrites() {
        val dir = Files.createTempDirectory("ns-dl").toFile()
        assertEquals("x-volume-1.epub", Downloads.reserve(dir, "x-volume-1.epub").name)
        assertEquals("x-volume-1 (1).epub", Downloads.reserve(dir, "x-volume-1.epub").name)
        assertEquals("x-volume-1 (2).epub", Downloads.reserve(dir, "x-volume-1.epub").name)
        assertEquals("noext", Downloads.reserve(dir, "noext").name)
        assertEquals("noext (1)", Downloads.reserve(dir, "noext").name)
    }
}
