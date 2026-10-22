package com.novelscraper.app.library

import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.ChapterItem
import com.novelscraper.app.extensions.SourceNovel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a refresh is allowed to delete.
 *
 * A refresh runs unattended every few hours against sites that fail in ways
 * that look like success. Before this was guarded, a source answering with an
 * empty or truncated chapter list deleted every chapter missing from it, and
 * `chapter_content` and `download_queue` cascade, so a whole downloaded novel
 * could go on one bad response.
 */
class ChapterMergeTest {

    /** A source whose chapter list can shrink between refreshes. */
    private class Source(var count: Int = 4) : SourceOrigin {
        override suspend fun name(pluginId: String) = "Fake"
        override suspend fun novel(pluginId: String, path: String) = SourceNovel(
            name = "Novel", path = path,
            chapters = (1..count).map { ChapterItem("Chapter $it", "$path/c$it") },
        )
        override suspend fun chapter(pluginId: String, path: String) = "<p>$path</p>"
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    private fun store(source: SourceOrigin) =
        LibraryStore(jdbcLibraryDriver(null), source)

    private suspend fun LibraryStore.count(id: Int) = chaptersFlow(id).first().size

    private suspend fun LibraryStore.downloadAll(id: Int) {
        enqueueDownloads(id, null)
        var guard = 0
        while (guard++ < 40) {
            val (chapterId, _, _) = nextQueued() ?: break
            downloadQueued(chapterId)
        }
    }

    @Test
    fun anEmptyAnswerDeletesNothing() = runBlocking<Unit> {
        val source = Source(count = 4)
        val lib = store(source)
        val id = lib.openSource("fake", "n", "Novel", null).also { lib.refresh(it) }
        lib.setInLibrary(id, true)
        assertEquals(4, lib.count(id))

        source.count = 0                       // the parser broke, or the site erred
        lib.refresh(id)

        assertEquals(4, lib.count(id), "no novel has zero chapters: keep what is known")
    }

    @Test
    fun aShortListKeepsWhatCannotBeFetchedAgain() = runBlocking<Unit> {
        val source = Source(count = 4)
        val lib = store(source)
        val id = lib.openSource("fake", "n", "Novel", null).also { lib.refresh(it) }
        lib.setInLibrary(id, true)
        lib.downloadAll(id)
        lib.setRead(id, listOf(3), true)
        assertEquals(4, lib.libraryFlow().first()[0].downloadedCount, "all four downloaded")

        source.count = 2                       // a page of a paged list went missing
        lib.refresh(id)

        val left = lib.chaptersFlow(id).first()
        assertEquals(4, left.size, "downloads and read marks are not thrown away on a short list")
        assertTrue(left.all { it.downloaded }, "the downloaded text survived")
        assertTrue(left.single { it.position == 3 }.read, "and so did the read mark")
    }

    @Test
    fun aChapterHoldingNothingIsStillDropped() = runBlocking<Unit> {
        val source = Source(count = 4)
        val lib = store(source)
        val id = lib.openSource("fake", "n", "Novel", null).also { lib.refresh(it) }
        lib.setInLibrary(id, true)

        source.count = 2                       // the source really did drop two
        lib.refresh(id)

        assertEquals(2, lib.count(id),
            "nothing was downloaded or read, so there is nothing to protect")
    }

    /** A source whose chapters are whatever [paths] says now. */
    private class Listed(var paths: List<String>) : SourceOrigin {
        override suspend fun name(pluginId: String) = "Fake"
        override suspend fun novel(pluginId: String, path: String) = SourceNovel(
            name = "Novel", path = path,
            chapters = paths.map { ChapterItem("Chapter ${it.substringAfterLast('/')}", it) },
        )
        override suspend fun chapter(pluginId: String, path: String) = "<p>$path</p>"
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    private suspend fun LibraryStore.positions(id: Int) = chaptersFlow(id).first().map { it.position }

    @Test
    fun aChapterDroppedFromTheMiddleKeepsAPositionOfItsOwn() = runBlocking<Unit> {
        val source = Listed((1..5).map { "n/c$it" })
        val lib = store(source)
        val id = lib.openSource("fake", "n", "Novel", null).also { lib.refresh(it) }
        lib.setInLibrary(id, true)
        lib.setRead(id, listOf(3), true)       // read, so it is kept when it goes

        source.paths = source.paths - "n/c3"   // the site took chapter 3 down
        lib.refresh(id)

        val positions = lib.positions(id)
        assertEquals(positions.distinct(), positions, "two chapters at one position: $positions")
        assertEquals(listOf(1, 2, 3, 4, 5), positions.sorted())
        for (p in positions) lib.chapter(id, p)   // each opens: one row per position
        val kept = lib.chaptersFlow(id).first().single { it.read }
        assertEquals(5, kept.position, "kept after the ones the source still lists")

        // Marking one position marks one chapter.
        lib.setRead(id, listOf(3), true)
        assertEquals(2, lib.chaptersFlow(id).first().count { it.read })
    }

    @Test
    fun aSourceThatChangesEveryAddressKeepsReadChaptersApart() = runBlocking<Unit> {
        val source = Listed((1..4).map { "old/c$it" })
        val lib = store(source)
        val id = lib.openSource("fake", "n", "Novel", null).also { lib.refresh(it) }
        lib.setInLibrary(id, true)
        lib.setRead(id, listOf(1, 2), true)

        source.paths = (1..4).map { "new/c$it" }   // a new URL scheme
        lib.refresh(id)

        val positions = lib.positions(id)
        assertEquals(positions.distinct(), positions, "two chapters at one position: $positions")
        assertEquals(6, positions.size, "the four listed, and the two read ones kept")
        for (p in positions) lib.chapter(id, p)
    }
}
