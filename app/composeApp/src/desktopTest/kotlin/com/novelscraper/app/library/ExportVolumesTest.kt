package com.novelscraper.app.library

import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.epub.Epub
import com.novelscraper.app.extensions.ChapterItem
import com.novelscraper.app.extensions.SourceNovel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** Save as EPUB, a volume at a time: every stored chapter, in the right file. */
class ExportVolumesTest {

    private class Source : SourceOrigin {
        override suspend fun name(pluginId: String) = "Fake"
        override suspend fun novel(pluginId: String, path: String) =
            SourceNovel(name = "Long", path = path, chapters = (1..250).map { ChapterItem("Chapter $it", "$path/c$it") })
        override suspend fun chapter(pluginId: String, path: String) = "<p>text of ${path.substringAfterLast('/')}</p>"
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    @Test fun aLongNovelExportsAsVolumesThatReadBack() = runBlocking<Unit> {
        val lib = LibraryStore(jdbcLibraryDriver(null), Source())
        val id = lib.openSource("fake", "n", "Long", null).also { lib.refresh(it) }
        lib.setInLibrary(id, true)
        lib.enqueueDownloads(id, null)
        while (true) { val (c, _, _) = lib.nextQueued() ?: break; lib.downloadQueued(c) }

        val positions = lib.storedPositions(id)
        assertEquals(250, positions.size)
        val numbers = positions.map { lib.volumeOf(it) }.distinct()
        assertEquals(listOf(1, 2, 3), numbers)

        val zipped = ByteArrayOutputStream().also { out ->
            Epub.writeZip("long", "Long", "", numbers, { lib.storedVolume(id, it) }, out)
        }.toByteArray()
        val read = buildMap {
            ZipInputStream(ByteArrayInputStream(zipped)).use { z ->
                while (true) { val e = z.nextEntry ?: break; put(e.name, Epub.read(ByteArrayInputStream(z.readBytes()))) }
            }
        }
        assertEquals(listOf(100, 100, 50), read.keys.sorted().map { read.getValue(it).chapters.size })
        val third = read.getValue(read.keys.sorted()[2]).chapters
        assertEquals("Chapter 201", third.first().title)
        assertEquals(true, "text of c250" in third.last().html)
    }
}
