package com.novelscraper.app.library

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Two devices with their own libraries and clocks, syncing through one server. */
class LibrarySyncTest {

    private class Source : SourceOrigin {
        override suspend fun name(pluginId: String) = "Fake Source"
        override suspend fun novel(pluginId: String, path: String) = SourceNovel(
            name = "The Novel", path = path,
            chapters = (1..5).map { ChapterItem("Chapter $it", "$path/c$it") },
        )
        override suspend fun chapter(pluginId: String, path: String) = "<p>$path</p>"
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    /** One device's connection to the shared server. */
    private class Link(val store: LibraryStoreTest.FakeSync) : ServerOrigin {
        override val enabled = true
        var offline = false
        override suspend fun sync(request: SyncRequest): SyncResponse {
            if (offline) throw IOException("offline")
            return store.sync(request)
        }
    }

    private class Device(server: LibraryStoreTest.FakeSync, var clock: Long) {
        val link = Link(server)
        val lib = LibraryStore(jdbcLibraryDriver(null), Source(), link, now = { clock })
    }

    private val server = LibraryStoreTest.FakeSync()
    private val a = Device(server, clock = 1_000_000)
    private val b = Device(server, clock = 1_000_000)

    /** Device [d]'s local id for the fake novel, adding it to the library. */
    private suspend fun addNovel(d: Device): Int {
        val id = d.lib.openSource("fake", "n", "The Novel", null)
        d.lib.refresh(id)
        d.lib.setInLibrary(id, true)
        return id
    }

    private suspend fun libraryOf(d: Device) = d.lib.libraryFlow().first()

    @Test
    fun aNovelAndEverythingAboutItReachTheOtherDevice() = runBlocking {
        val idA = addNovel(a)
        a.lib.markOpened(idA, 2)
        a.lib.saveProgress(idA, 2, 0.3f, 5)
        a.lib.setRating(idA, 4)
        val later = a.lib.createCollection("Later")
        a.lib.setBookCollections(idA, listOf(later))
        a.lib.syncNow()

        b.lib.syncNow()
        val novel = libraryOf(b).single()
        assertEquals("The Novel", novel.title)
        assertEquals(4, novel.rating)
        val shelf = b.lib.collectionsFlow().first().single()
        assertEquals("Later", shelf.name)
        assertEquals(listOf(shelf.id), novel.collectionIds)

        // Its chapters aren't on B until fetched; then the waiting records apply.
        assertTrue(b.lib.progress(novel.id).readPositions.isEmpty())
        b.lib.refresh(novel.id)
        val p = b.lib.progress(novel.id)
        assertEquals(setOf(2), p.readPositions)
        assertEquals(2, p.lastPosition); assertEquals(0.3f, p.scroll); assertEquals(5, p.sentence)
    }

    @Test
    fun concurrentEditsConverge() = runBlocking {
        val idA = addNovel(a); a.lib.syncNow()
        b.lib.syncNow()
        val idB = libraryOf(b).single().id
        b.lib.refresh(idB)

        // Both offline, editing the same things.
        a.link.offline = true; b.link.offline = true
        a.clock = 2_000_000; a.lib.setRead(idA, listOf(3), true)       // A marks chapter 3...
        b.clock = 3_000_000; b.lib.setRead(idB, listOf(3), true)
        b.clock = 3_000_001; b.lib.setRead(idB, listOf(3), false)     // ...B unmarks it later.
        a.clock = 5_000_000; a.lib.setRating(idA, 5)                  // A rates later...
        b.clock = 4_000_000; b.lib.setRating(idB, 2)                  // ...than B.
        a.clock = 6_000_000; a.lib.markOpened(idA, 4)                 // A's position is newer,
        b.clock = 5_500_000; b.lib.markOpened(idB, 5)                 // B's older.

        a.link.offline = false; b.link.offline = false
        a.lib.syncNow(); b.lib.syncNow(); a.lib.syncNow()

        for ((d, id) in listOf(a to idA, b to idB)) {
            val p = d.lib.progress(id)
            assertFalse(3 in p.readPositions, "chapter 3: the later unmark wins")
            assertEquals(4, p.lastPosition, "the newer resume point wins")
            assertTrue(4 in p.readPositions && 5 in p.readPositions, "read marks from both")
            assertEquals(5, d.lib.book(id)!!.rating, "the later rating wins")
        }
    }

    @Test
    fun removalsReachTheOtherDevice() = runBlocking {
        val idA = addNovel(a)
        val col = a.lib.createCollection("Soon")
        a.lib.syncNow(); b.lib.syncNow()
        assertEquals(1, libraryOf(b).size)
        assertEquals(1, b.lib.collectionsFlow().first().size)

        a.clock += 1000
        a.lib.setInLibrary(idA, false)
        a.lib.deleteCollection(col)
        a.lib.syncNow(); b.lib.syncNow()
        assertTrue(libraryOf(b).isEmpty())
        assertTrue(b.lib.collectionsFlow().first().isEmpty())
    }

    @Test
    fun enrollingDoesNotUndoNewerChanges() = runBlocking {
        // A has synced a rating of 5.
        val idA = addNovel(a)
        a.lib.setRating(idA, 5)
        a.lib.syncNow()
        // B had the same novel from before sync existed, rated 3, and a shelf of its own.
        val idB = addNovel(b)
        b.lib.setRating(idB, 3)
        b.lib.createCollection("Mine")
        b.lib.resetSync()                  // as if it never synced: its library gets enrolled
        b.lib.syncNow()
        assertEquals(5, b.lib.book(idB)!!.rating, "A's real change beats B's enrolled state")
        a.lib.syncNow()
        assertEquals(listOf("Mine"), a.lib.collectionsFlow().first().map { it.name }, "B's own shelf reaches A")
    }

    @Test
    fun aClockBehindStillOrdersItsChangesAfterWhatItSaw() = runBlocking {
        val idA = addNovel(a)
        a.clock = 9_000_000
        a.lib.setRating(idA, 5)
        a.lib.syncNow()
        b.clock = 1_000                    // B's clock is far behind
        b.lib.syncNow()
        val idB = libraryOf(b).single().id
        b.lib.setRating(idB, 1)            // made after seeing A's rating
        b.lib.syncNow(); a.lib.syncNow()
        assertEquals(1, a.lib.book(idA)!!.rating)
    }

    @Test
    fun novelsNotInTheLibraryStayPrivate() = runBlocking {
        val id = a.lib.openSource("fake", "n", "The Novel", null)
        a.lib.refresh(id)
        a.lib.markOpened(id, 1)
        a.lib.syncNow(); b.lib.syncNow()
        assertTrue(libraryOf(b).isEmpty())
        assertTrue(server.records.isEmpty(), "nothing about a browsed novel was sent")
    }
}
