package com.novelscraper.app.net

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsTest {
    @Test fun fileNameRule() {
        assertEquals("renegade-immortal-volume-3.epub", downloadFileName("renegade-immortal", 3))
        assertEquals("a_b_c.epub", downloadFileName("a b/c"))
        assertEquals("book.epub", downloadFileName("???"))
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
