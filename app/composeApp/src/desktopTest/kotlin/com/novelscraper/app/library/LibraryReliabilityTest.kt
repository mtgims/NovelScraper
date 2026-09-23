package com.novelscraper.app.library

import com.novelscraper.app.db.LibraryBackup
import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.SourceNovel
import com.novelscraper.app.extensions.ChapterItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Downloads that fail, and the copy taken before a migration. */
class LibraryReliabilityTest {

    private class Source(val failFrom: Int = Int.MAX_VALUE) : SourceOrigin {
        var calls = 0
        override suspend fun name(pluginId: String) = "Fake"
        override suspend fun novel(pluginId: String, path: String) = SourceNovel(
            name = "Novel", path = path,
            chapters = (1..4).map { ChapterItem("Chapter $it", "$path/c$it") },
        )
        override suspend fun chapter(pluginId: String, path: String): String {
            calls++
            val n = path.substringAfterLast('c').toIntOrNull() ?: 0
            if (n >= failFrom) throw java.io.IOException("the site is down")
            return "<p>$path</p>"
        }
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    private fun store(source: SourceOrigin) =
        LibraryStore(jdbcLibraryDriver(null), source, LibraryStoreTest.offlineServer())

    @Test
    fun aChapterThatKeepsFailingIsLeftAndTheRestGoOn() = runBlocking<Unit> {
        val source = Source(failFrom = 3)          // chapters 3 and 4 fail
        val lib = store(source)
        val id = lib.openSource("fake", "n", "Novel", null).also { lib.refresh(it) }
        lib.setInLibrary(id, true)
        lib.enqueueDownloads(id, null)

        var guard = 0
        while (guard++ < 40) {
            val (chapterId, _, attempts) = lib.nextQueued() ?: break
            try {
                lib.downloadQueued(chapterId)
            } catch (e: Exception) {
                lib.downloadFailed(chapterId, attempts, tries = 3)
            }
        }
        assertEquals(2, lib.libraryFlow().first()[0].downloadedCount, "the chapters that worked are here")
        assertEquals(2L, lib.failedDownloadsFlow().first(), "the two that didn't are remembered")
        assertNull(lib.nextQueued(), "and the queue isn't stuck on them")
        assertEquals(2 + 3 + 3, source.calls, "two chapters fetched once, the two failing ones tried three times")

        lib.retryFailedDownloads()
        assertEquals(0L, lib.failedDownloadsFlow().first())
        assertTrue(lib.nextQueued() != null, "they are back in the queue")
        lib.forgetFailedDownloads()
    }

    @Test
    fun theLibraryIsCopiedBeforeAMigration() {
        val dir = Files.createTempDirectory("lib-backup").toFile()
        val db = File(dir, "library.db").apply { writeText("pretend database") }

        assertNull(LibraryBackup.beforeMigration(db, onDiskVersion = 3, schemaVersion = 3), "nothing to migrate")
        assertNull(LibraryBackup.beforeMigration(File(dir, "missing.db"), 1, 3), "no file, no copy")

        val backup = LibraryBackup.beforeMigration(db, onDiskVersion = 1, schemaVersion = 3)
        assertTrue(backup != null && backup.exists(), "a copy is taken before migrating")
        assertEquals("pretend database", backup!!.readText())

        // Only the newest copies are kept.
        for (v in 2..(LibraryBackup.KEEP + 3)) {
            LibraryBackup.beforeMigration(db, onDiskVersion = v.toLong(), schemaVersion = 99)
            Thread.sleep(5)
        }
        val kept = dir.listFiles { f -> f.name.endsWith(".bak") }!!.size
        assertEquals(LibraryBackup.KEEP, kept)
        dir.deleteRecursively()
    }

    @Test
    fun theUpdateMessageReadsWell() {
        assertEquals("No new chapters.", LibraryUpdates.message(0, 0))
        assertEquals("1 new chapter.", LibraryUpdates.message(1, 0))
        assertEquals("7 new chapters, 1 source couldn't be reached.", LibraryUpdates.message(7, 1))
        assertEquals("No new chapters, 2 sources couldn't be reached.", LibraryUpdates.message(0, 2))
    }
}
