package com.novelscraper.app.library

import com.novelscraper.app.data.BookCollectionsUpdate
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.BookUpdate
import com.novelscraper.app.data.ChapterListItem
import com.novelscraper.app.data.CollectionRead
import com.novelscraper.app.data.CollectionUpdate
import com.novelscraper.app.data.ProgressUpdate
import com.novelscraper.app.data.ReadingProgressRead
import com.novelscraper.app.data.SyncChange
import com.novelscraper.app.data.SyncRequest
import com.novelscraper.app.data.SyncResponse
import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.ChapterItem
import com.novelscraper.app.extensions.SourceNovel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryStoreTest {

    /** A source with one novel whose chapter list the test can change. */
    private class FakeSource : SourceOrigin {
        var chapters = (1..5).map { ChapterItem("Chapter $it", "n/c$it", chapterNumber = it.toDouble()) }
        val fetched = ArrayList<String>()
        override suspend fun name(pluginId: String) = "Fake Source"
        override suspend fun novel(pluginId: String, path: String) =
            SourceNovel(name = "The Novel", path = path, author = "Someone", summary = " A tale. ", chapters = chapters)
        override suspend fun chapter(pluginId: String, path: String): String {
            fetched += path; return "<p>text of $path</p>"
        }
        override suspend fun webUrl(pluginId: String, path: String) = "https://fake/$path"
    }

    /** The server's sync records, shared by every device (like /api/sync). */
    class FakeSync {
        data class Rec(val c: SyncChange, val seq: Long)
        val records = LinkedHashMap<Pair<String, String>, Rec>()
        private var seq = 0L

        /** What the server's seed makes from its own tables (clock 1). */
        fun seed(kind: String, key: String, value: String) {
            records[kind to key] = Rec(SyncChange(kind, key, value, 1, "seed"), ++seq)
        }

        fun sync(r: SyncRequest): SyncResponse {
            for (c in r.changes) {
                val dev = c.device.ifEmpty { r.device }
                val old = records[c.kind to c.key]?.c
                if (old == null || c.ts > old.ts || (c.ts == old.ts && dev > old.device))
                    records[c.kind to c.key] = Rec(c.copy(device = dev), ++seq)
            }
            val out = records.values.filter { it.seq > r.cursor }.sortedBy { it.seq }
            return SyncResponse(out.lastOrNull()?.seq ?: r.cursor, out.map { it.c }, false)
        }

        fun value(kind: String, key: String) = records[kind to key]?.c?.value
    }

    /** A server holding two novels; can be taken offline. */
    class FakeServer(val syncStore: FakeSync = FakeSync()) : ServerOrigin {
        override var enabled = true
        var offline = false
        val books = mutableListOf(
            BookRead(10, "a", "site-a", "Server A", "Author A", "en", rating = 4, collection_ids = listOf(100)),
            BookRead(11, "b", "site-b", "Server B", "Author B", "en"),
        )
        val cols = mutableListOf(CollectionRead(100, "Favourites"))
        val progress = HashMap<Int, ReadingProgressRead>()
        val sent = ArrayList<Pair<Int, ProgressUpdate>>()
        val ratings = ArrayList<Pair<Int, Int?>>()
        val deleted = ArrayList<Int>()

        private fun net() { if (offline) throw IOException("offline") }
        override suspend fun books(): List<BookRead> { net(); return books.toList() }
        override suspend fun book(id: Int): BookRead { net(); return books.first { it.id == id } }
        override suspend fun collections(): List<CollectionRead> { net(); return cols.toList() }
        override suspend fun chapters(id: Int): List<ChapterListItem> {
            net(); return (1..4).map { ChapterListItem(it, "$it", "S$id ch $it", volume = if (it <= 2) 1 else 2) }
        }
        override suspend fun chapter(id: Int, position: Int): String { net(); return "<p>server $id/$position</p>" }
        override suspend fun progress(id: Int): ReadingProgressRead {
            net()
            return progress[id] ?: ReadingProgressRead(1, 0f, emptyList(), 4, 0, 4, 0f, 0, 0, 0f, 0f)
        }
        override suspend fun putProgress(id: Int, update: ProgressUpdate) { net(); sent += id to update }
        override suspend fun editBook(id: Int, update: BookUpdate) { net(); ratings += id to update.rating }
        override suspend fun setBookCollections(id: Int, update: BookCollectionsUpdate) { net() }
        override suspend fun updateCollection(id: Int, update: CollectionUpdate) { net() }
        override suspend fun deleteCollection(id: Int) { net() }
        override suspend fun deleteBook(id: Int) { net(); deleted += id }
        override suspend fun sync(request: SyncRequest): SyncResponse { net(); return syncStore.sync(request) }
    }

    companion object {
        /** A server that is never signed in (for tests that only use sources). */
        fun offlineServer(): ServerOrigin = FakeServer().also { it.enabled = false }
    }

    private var clock = 1_000L
    private val source = FakeSource()
    private val server = FakeServer()
    private val lib = LibraryStore(jdbcLibraryDriver(null), source, server, now = { clock++ })

    @Test
    fun sourceNovelIsOpenedRefreshedAndAdded() = runBlocking {
        val id = lib.openSource("fake", "n", "Listed name", "cover.jpg")
        assertEquals(id, lib.openSource("fake", "n", "Listed name", "cover.jpg"), "same row on re-open")
        assertTrue(lib.libraryFlow().first().isEmpty(), "opened, not added")

        lib.refresh(id)
        val b = lib.book(id)!!
        assertEquals("The Novel", b.title)
        assertEquals("A tale.", b.summary)
        assertEquals("Fake Source", b.site)
        assertEquals("cover.jpg", b.cover, "kept the list's cover when the page has none")
        assertEquals("https://fake/n", b.webUrl)
        assertEquals((1..5).toList(), lib.chaptersFlow(id).first().map { it.position })

        lib.setInLibrary(id, true)
        val grid = lib.libraryFlow().first()
        assertEquals(listOf(id), grid.map { it.id })
        assertEquals(5, grid[0].chapterCount)
        assertEquals(5, grid[0].unreadCount)
    }

    @Test
    fun chapterTextIsStreamedOnceThenCached() = runBlocking {
        val id = lib.openSource("fake", "n", "x", null).also { lib.refresh(it) }
        val c = lib.chapter(id, 2)
        assertEquals("<p>text of n/c2</p>", c.content)
        assertTrue(c.has_prev && c.has_next)
        lib.chapter(id, 2)
        assertEquals(listOf("n/c2"), source.fetched, "second read came from the cache")
        assertFalse(lib.chapter(id, 5).has_next)
    }

    @Test
    fun changedChapterListKeepsReadStateByPath() = runBlocking {
        val id = lib.openSource("fake", "n", "x", null).also { lib.refresh(it) }
        lib.markOpened(id, 3)   // n/c3
        lib.chapter(id, 3)
        // The source inserts a chapter at the front and drops the last one.
        source.chapters = listOf(ChapterItem("Prologue", "n/p")) + source.chapters.dropLast(1)
        lib.refresh(id)
        val list = lib.chaptersFlow(id).first()
        assertEquals(listOf("Prologue", "Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4"), list.map { it.title })
        assertEquals(listOf(4), list.filter { it.read }.map { it.position }, "n/c3 is now position 4, still read")
        assertEquals(4, lib.progress(id).lastPosition, "resume point follows the chapter")
        lib.chapter(id, 4)
        assertEquals(listOf("n/c3"), source.fetched, "its cached text followed it too")
    }

    @Test
    fun progressEdits() = runBlocking {
        val id = lib.openSource("fake", "n", "x", null).also { lib.refresh(it) }
        lib.markOpened(id, 2)
        lib.saveScroll(id, 2, 0.4f)
        var p = lib.progress(id)
        assertEquals(2, p.lastPosition); assertEquals(0.4f, p.scroll); assertEquals(setOf(2), p.readPositions)
        lib.markOpened(id, 2)
        assertEquals(0.4f, lib.progress(id).scroll, "re-opening the same chapter keeps its scroll")
        lib.setRead(id, listOf(1, 3), true)
        lib.setRead(id, listOf(2), false)
        assertEquals(setOf(1, 3), lib.progress(id).readPositions)
        lib.markAllRead(id)
        assertEquals(100f, lib.progress(id).percent)
        lib.resetProgress(id)
        p = lib.progress(id)
        assertTrue(p.readPositions.isEmpty()); assertNull(p.lastPosition)
    }

    @Test
    fun downloadsAreKeptAndRemovable() = runBlocking {
        val id = lib.openSource("fake", "n", "x", null).also { lib.refresh(it) }
        lib.setInLibrary(id, true)
        lib.chapter(id, 1)                        // cached by streaming first
        lib.enqueueDownloads(id, null)
        assertEquals(5L, lib.queuedFlow().first())
        var fetchedFromSite = 0
        while (true) {
            val (chapterId, _, _) = lib.nextQueued() ?: break
            if (lib.downloadQueued(chapterId)) fetchedFromSite++
        }
        assertEquals(4, fetchedFromSite, "the cached chapter wasn't fetched again")
        assertEquals(5, lib.libraryFlow().first()[0].downloadedCount)
        assertTrue(lib.chaptersFlow(id).first().all { it.downloaded })
        lib.removeDownloads(id)
        assertEquals(0, lib.libraryFlow().first()[0].downloadedCount)
    }

    @Test
    fun deletingANovelDeletesItsChapters() = runBlocking {
        val id = lib.openSource("fake", "n", "x", null).also { lib.refresh(it) }
        lib.chapter(id, 1)
        lib.delete(id)
        assertNull(lib.book(id))
        assertTrue(lib.chaptersFlow(id).first().isEmpty())
    }

    @Test
    fun serverLibraryIsImported() = runBlocking {
        assertEquals(2, lib.pullServer())
        val grid = lib.libraryFlow().first()
        assertEquals(listOf("Server A", "Server B"), grid.map { it.title })
        val a = grid[0]
        assertEquals(10, a.serverId); assertEquals(4, a.rating); assertEquals("site-a", a.site)
        assertEquals(4, lib.chaptersFlow(a.id).first().size)
        assertEquals("<p>server 10/2</p>", lib.chapter(a.id, 2).content)

        // Pulling again adds nothing; a novel deleted on the server goes.
        server.books.removeAt(1)
        assertEquals(0, lib.pullServer())
        assertEquals(listOf("Server A"), lib.libraryFlow().first().map { it.title })
    }

    @Test
    fun serverProgressAndShelvesArriveBySync() = runBlocking {
        // What the server seeds from its tables for novel 10 on the first sync.
        val sync = server.syncStore
        sync.seed("progress", "srv:10", """{"chapter":"3","scroll":0.5,"sentence":12}""")
        for (p in 1..3) sync.seed("read", "srv:10\t$p", """{"read":true}""")
        sync.seed("collection", "srv:100", """{"name":"Favourites","sort":1,"deleted":false}""")
        sync.seed("shelf", "srv:100\tsrv:10", """{"member":true}""")
        sync.seed("novel", "srv:10", """{"server_id":10,"title":"Server A","author":"Author A","site":"site-a","in_library":true}""")

        // Sync first (the novel arrives before its chapters), then the import
        // fetches the chapter list and the waiting records apply.
        assertTrue(lib.syncNow())
        lib.pullServer()
        val a = lib.libraryFlow().first().first { it.serverId == 10 }
        val p = lib.progress(a.id)
        assertEquals(setOf(1, 2, 3), p.readPositions); assertEquals(3, p.lastPosition)
        assertEquals(0.5f, p.scroll); assertEquals(12, p.sentence)
        val fav = lib.collectionsFlow().first().single()
        assertEquals("Favourites", fav.name)
        assertEquals(listOf(fav.id), a.collectionIds)
        assertEquals(1, lib.libraryFlow().first().count { it.serverId == 10 }, "one row, however it arrived")
    }

    @Test
    fun changesMadeOfflineAreSentLater() = runBlocking {
        lib.pullServer()
        val a = lib.libraryFlow().first().first { it.serverId == 10 }
        server.offline = true
        lib.markOpened(a.id, 4)
        lib.setRating(a.id, 2)
        assertFailsWith<IOException> { lib.syncNow() }
        assertTrue(lib.pendingSyncCount() > 0)
        assertEquals(setOf(4), lib.progress(a.id).readPositions, "kept here meanwhile")

        server.offline = false
        assertTrue(lib.syncNow())
        assertEquals(0L, lib.pendingSyncCount())
        assertEquals("""{"read":true}""", server.syncStore.value("read", "srv:10\t4"))
        assertEquals("""{"rating":2}""", server.syncStore.value("rating", "srv:10"))
    }

    @Test
    fun signedOutServerIsLeftAlone() = runBlocking {
        server.enabled = false
        assertEquals(0, lib.pullServer())
        assertTrue(lib.libraryFlow().first().isEmpty())
    }
}
