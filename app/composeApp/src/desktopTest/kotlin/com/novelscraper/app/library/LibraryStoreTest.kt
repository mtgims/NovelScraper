package com.novelscraper.app.library

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

    /** The sync endpoint; can be taken offline. */
    class FakeServer(val syncStore: FakeSync = FakeSync()) : ServerOrigin {
        override var enabled = true
        var offline = false

        override suspend fun sync(request: SyncRequest): SyncResponse {
            if (offline) throw IOException("offline")
            return syncStore.sync(request)
        }
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
    fun recordsFromAnotherDeviceArriveAndApply() = runBlocking {
        // What another device has already synced about a novel this one has never seen.
        val key = "src:fake\tn"
        val sync = server.syncStore
        sync.seed("novel", key, """{"plugin":"fake","path":"n","title":"The Novel","author":"Someone","in_library":true}""")
        sync.seed("progress", key, """{"chapter":"n/c3","scroll":0.5,"sentence":12}""")
        for (p in 1..3) sync.seed("read", "$key\tn/c$p", """{"read":true}""")
        sync.seed("collection", "c-fav", """{"name":"Favourites","sort":1,"deleted":false}""")
        sync.seed("shelf", "c-fav\t$key", """{"member":true}""")

        // The novel arrives before its chapters; refreshing fetches them and the
        // waiting records then apply.
        assertTrue(lib.syncNow())
        val a = lib.libraryFlow().first().single()
        assertEquals("The Novel", a.title)
        lib.refresh(a.id)

        val p = lib.progress(a.id)
        assertEquals(setOf(1, 2, 3), p.readPositions); assertEquals(3, p.lastPosition)
        assertEquals(0.5f, p.scroll); assertEquals(12, p.sentence)
        val fav = lib.collectionsFlow().first().single()
        assertEquals("Favourites", fav.name)
        assertEquals(listOf(fav.id), lib.book(a.id)!!.collectionIds)
        assertEquals(1, lib.libraryFlow().first().size, "one row, however it arrived")
    }

    @Test
    fun changesMadeOfflineAreSentLater() = runBlocking {
        val id = lib.openSource("fake", "n", "The Novel", null).also { lib.refresh(it) }
        lib.setInLibrary(id, true)
        assertTrue(lib.syncNow())

        server.offline = true
        lib.markOpened(id, 4)
        lib.setRating(id, 2)
        assertFailsWith<IOException> { lib.syncNow() }
        assertTrue(lib.pendingSyncCount() > 0)
        assertEquals(setOf(4), lib.progress(id).readPositions, "kept here meanwhile")

        server.offline = false
        assertTrue(lib.syncNow())
        assertEquals(0L, lib.pendingSyncCount())
        assertEquals("""{"read":true}""", server.syncStore.value("read", "src:fake\tn\tn/c4"))
        assertEquals("""{"rating":2}""", server.syncStore.value("rating", "src:fake\tn"))
    }

    @Test
    fun signedOutMeansNoSync() = runBlocking {
        server.enabled = false
        val id = lib.openSource("fake", "n", "The Novel", null).also { lib.refresh(it) }
        lib.setInLibrary(id, true)
        assertFalse(lib.syncNow())
        assertTrue(server.syncStore.records.isEmpty(), "nothing was sent")
    }
}
