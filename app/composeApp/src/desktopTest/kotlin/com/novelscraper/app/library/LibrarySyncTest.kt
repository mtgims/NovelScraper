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
import kotlin.test.assertTrue

/** What sync records carry between two devices, whatever folder they go
 *  through (FolderSyncTest covers the folder itself). */
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

    private class Device(val folder: FakeFolder, var clock: Long) {
        private val keys = KeyCache()
        val lib = LibraryStore(jdbcLibraryDriver(null), Source(), now = { clock })
        suspend fun sync() = lib.syncFolder(folder, null, keys)
    }

    private val folder = FakeFolder()
    private val a = Device(folder, clock = 1_000_000)
    private val b = Device(folder, clock = 1_000_000)

    /** Device [d]'s local id for the fake novel, adding it to the library. */
    private suspend fun addNovel(d: Device): Int {
        val id = d.lib.openSource("fake", "n", "The Novel", null)
        d.lib.refresh(id)
        d.lib.setInLibrary(id, true)
        return id
    }

    private suspend fun libraryOf(d: Device) = d.lib.libraryFlow().first()

    @Test
    fun removalsReachTheOtherDevice() = runBlocking<Unit> {
        val idA = addNovel(a)
        val col = a.lib.createCollection("Soon")
        a.sync(); b.sync()
        assertEquals(1, libraryOf(b).size)
        assertEquals(1, b.lib.collectionsFlow().first().size)

        a.clock += 1000
        a.lib.setInLibrary(idA, false)
        a.lib.deleteCollection(col)
        a.sync(); b.sync()
        assertTrue(libraryOf(b).isEmpty())
        assertTrue(b.lib.collectionsFlow().first().isEmpty())
    }

    @Test
    fun enrollingDoesNotUndoNewerChanges() = runBlocking<Unit> {
        // A has synced a rating of 5.
        val idA = addNovel(a)
        a.lib.setRating(idA, 5)
        a.sync()
        // B had the same novel from before sync existed, rated 3, and a shelf of its own.
        val idB = addNovel(b)
        b.lib.setRating(idB, 3)
        b.lib.createCollection("Mine")
        b.lib.resetSync()                  // as if it never synced: its library gets enrolled
        b.sync()
        assertEquals(5, b.lib.book(idB)!!.rating, "A's real change beats B's enrolled state")
        a.sync()
        assertEquals(listOf("Mine"), a.lib.collectionsFlow().first().map { it.name }, "B's own shelf reaches A")
    }

    @Test
    fun aClockBehindStillOrdersItsChangesAfterWhatItSaw() = runBlocking<Unit> {
        val idA = addNovel(a)
        a.clock = 9_000_000
        a.lib.setRating(idA, 5)
        a.sync()
        b.clock = 1_000                    // B's clock is far behind
        b.sync()
        val idB = libraryOf(b).single().id
        b.lib.setRating(idB, 1)            // made after seeing A's rating
        b.sync(); a.sync()
        assertEquals(1, a.lib.book(idA)!!.rating)
    }

    @Test
    fun novelsNotInTheLibraryStayPrivate() = runBlocking<Unit> {
        val id = a.lib.openSource("fake", "n", "The Novel", null)
        a.lib.refresh(id)
        a.lib.markOpened(id, 1)
        a.sync(); b.sync()
        assertTrue(libraryOf(b).isEmpty())
        assertTrue(a.lib.sync.snapshot().none { "src:fake" in it.key }, "nothing about a browsed novel was sent")
    }
}
