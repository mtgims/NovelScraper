package com.novelscraper.app.library

import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.ChapterItem
import com.novelscraper.app.extensions.SourceNovel
import com.novelscraper.app.sync.FakeFolder
import com.novelscraper.app.sync.KeyCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Moving a novel from one source to another, here and on the other devices. */
class MigrationTest {

    /** Two sources with the same novel: "old" lists 20 chapters, "new" a prologue then 25. */
    private class Sources : SourceOrigin {
        override suspend fun name(pluginId: String) = if (pluginId == "old") "Old Site" else "New Site"
        override suspend fun novel(pluginId: String, path: String) = SourceNovel(
            name = "The Novel", path = path,
            chapters = if (pluginId == "old") (1..20).map { ChapterItem("Chapter $it", "o/c$it") }
            else listOf(ChapterItem("Prologue", "n/p")) + (1..25).map { ChapterItem("Chapter $it", "n/c$it") },
        )
        override suspend fun chapter(pluginId: String, path: String) = "<p>$path</p>"
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    private class Settings : NovelSettings {
        val values = HashMap<String, String>()
        override fun all(): Map<String, String> = HashMap(values)
        override fun received(novelKey: String, value: String) { values[novelKey] = value }
    }

    private class Device(val folder: FakeFolder, var clock: Long) {
        private val keys = KeyCache()
        val settings = Settings()
        val lib = LibraryStore(jdbcLibraryDriver(null), Sources(), now = { clock }).also { it.novelSettings = settings }
        suspend fun sync() = lib.syncFolder(folder, null, keys)
    }

    private val folder = FakeFolder()
    private val a = Device(folder, clock = 1_000_000)
    private val b = Device(folder, clock = 1_000_000)

    /** The old novel in A's library, read to chapter 10, resumed in 11, rated, on a shelf, second in line. */
    private suspend fun readOnTheOldSource(): Int = with(a.lib) {
        val other = openSource("other", "x", "Another Novel", null).also { refresh(it); setInLibrary(it, true) }
        val id = openSource("old", "o", "The Novel", null)
        refresh(id)
        setInLibrary(id, true)
        reorder(listOf(id, other))
        setRead(id, (1..10).toList(), true)
        markOpened(id, 11)
        saveProgress(id, 11, 0.4f, 17)
        setRating(id, 4)
        setBookCollections(id, listOf(createCollection("Favourites")))
        noteNovelSettings(syncKeyOf(id)!!, """{"voices":"x"}""")
        enqueueDownloads(id, listOf(1, 2))
        while (true) downloadQueued(nextQueued()?.first ?: break)
        id
    }

    private suspend fun moveToTheNewSource(fromId: Int, keepOld: Boolean = false): Int {
        val to = a.lib.openSource("new", "n", "The Novel", null)
        a.lib.refresh(to)
        a.lib.migrate(fromId, to, keepOld = keepOld, downloadAgain = true)
        return to
    }

    private suspend fun downloaded(id: Int) = a.lib.chaptersFlow(id).first().count { it.downloaded }

    @Test fun thePreviewSaysWhatMovesAndWhere() = runBlocking<Unit> {
        val from = readOnTheOldSource()
        val to = a.lib.openSource("new", "n", "The Novel", null).also { a.lib.refresh(it) }
        val p = a.lib.migrationPreview(from, to)
        assertEquals(20, p.chaptersHere)
        assertEquals(26, p.chaptersThere)
        assertEquals(11, p.readHere, "1 to 10, and 11 opened")
        assertEquals(12, p.readThere, "the prologue and 1 to 11")
        assertEquals("Chapter 11", p.resumeHere)
        assertEquals("Chapter 11", p.resumeThere)
        assertTrue(p.exact && p.byNumber && !p.pastTheEnd)
        assertEquals(2, p.downloadedHere)
        assertEquals("Old Site", p.siteHere)
    }

    @Test fun everythingAboutTheNovelMovesAndTheOldOneLeaves() = runBlocking<Unit> {
        val from = readOnTheOldSource()
        val to = moveToTheNewSource(from)

        val lib = a.lib.libraryFlow().first()
        assertEquals(listOf(to, lib[1].id), lib.map { it.id }, "the new one in the old one's place, first")
        assertFalse(lib.any { it.id == from }, "the old one left the library")
        val moved = a.lib.book(to)!!
        assertEquals(4, moved.rating)
        assertEquals(1, moved.collectionIds.size)
        val progress = a.lib.progress(to)
        assertEquals((1..12).toSet(), progress.readPositions)
        assertEquals(12, progress.lastPosition, "chapter 11, one place later for the prologue")
        assertEquals(0.4f, progress.scroll, 0.001f)
        assertNull(progress.sentence, "a sentence number belongs to the old site's text")
        assertEquals(0, downloaded(from), "the old downloads went with it")
        assertEquals(3L, a.lib.queuedForBookFlow(to).first(), "the same chapters from the new source, and the prologue before them")
        assertEquals("""{"voices":"x"}""", a.settings.values[a.lib.syncKeyOf(to)])
    }

    @Test fun keepingTheOldOneLeavesItAndItsDownloadsAlone() = runBlocking<Unit> {
        val from = readOnTheOldSource()
        val to = moveToTheNewSource(from, keepOld = true)
        val ids = a.lib.libraryFlow().first().map { it.id }
        assertTrue(from in ids && to in ids)
        assertEquals(2, downloaded(from))
    }

    @Test fun theOtherDeviceFollows() = runBlocking<Unit> {
        val from = readOnTheOldSource()
        a.sync(); b.sync()
        assertEquals(2, b.lib.libraryFlow().first().size)

        a.clock += 1000
        moveToTheNewSource(from)
        a.sync(); b.sync()

        val lib = b.lib.libraryFlow().first()
        assertEquals(listOf("new", "other"), lib.map { it.pluginId }, "the old one gone, the new one in its place")
        val id = lib[0].id
        b.lib.refresh(id)    // B fetches the chapter list when the novel is opened or checked
        val progress = b.lib.progress(id)
        assertEquals((1..12).toSet(), progress.readPositions)
        assertEquals(12, progress.lastPosition)
        val book = b.lib.book(id)!!
        assertEquals(4, book.rating)
        assertEquals(listOf("Favourites"), b.lib.collectionsFlow().first().filter { it.id in book.collectionIds }.map { it.name })
        assertEquals("""{"voices":"x"}""", b.settings.values[b.lib.syncKeyOf(id)])
    }
}
