package com.novelscraper.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class EpubNameTest {
    @Test fun addsExtensionToATitle() =
        assertEquals("Renegade Immortal · volume 1.epub", epubName("Renegade Immortal · volume 1"))

    @Test fun keepsARealFileName() {
        assertEquals("book-volume-1.epub", epubName("book-volume-1.epub"))
        assertEquals("Book.EPUB", epubName("Book.EPUB"))
    }

    @Test fun fallsBackWhenUnknown() {
        assertEquals("book.epub", epubName(null))
        assertEquals("book.epub", epubName("  "))
    }
}
