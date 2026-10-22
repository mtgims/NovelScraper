package com.novelscraper.app.library

import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.SourceNovel
import com.novelscraper.app.sync.FakeFolder
import com.novelscraper.app.sync.KeyCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A library made by 0.31 (schema 1) opens in this version and enrolls in sync
 *  with it. Novels that lived on the server go, because the server no longer
 *  stores any; everything read from a source survives untouched. */
class LibraryMigrationTest {

    private object NoSource : SourceOrigin {
        override suspend fun name(pluginId: String) = ""
        override suspend fun novel(pluginId: String, path: String) = SourceNovel()
        override suspend fun chapter(pluginId: String, path: String) = ""
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    @Test
    fun aVersionOneLibraryMigratesAndEnrolls() = runBlocking<Unit> {
        // The schema snapshot of version 1, filled like a 0.31 library.
        val file = Files.createTempFile("library-v1", ".db").toFile()
        File("src/jvmSharedMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.executeUpdate("INSERT INTO book (id, server_id, title, in_library, sort_order, rating) VALUES (1, 10, 'Server A', 1, 1, 4)")
                s.executeUpdate("INSERT INTO book (id, plugin_id, path, title, in_library, sort_order) VALUES (2, 'rr', 'fiction/5', 'Source B', 1, 2)")
                s.executeUpdate("INSERT INTO book (id, plugin_id, path, title, in_library) VALUES (3, 'rr', 'fiction/6', 'Browsed', 0)")
                s.executeUpdate("INSERT INTO chapter (id, book_id, position, path, title, read) VALUES (1, 2, 1, 'fiction/5/1', 'One', 1)")
                s.executeUpdate("INSERT INTO chapter (id, book_id, position, path, title, read) VALUES (2, 2, 2, 'fiction/5/2', 'Two', 0)")
                s.executeUpdate("UPDATE book SET last_chapter_id = 1, scroll = 0.25 WHERE id = 2")
                s.executeUpdate("INSERT INTO chapter_content (chapter_id, html, kept, fetched_at) VALUES (1, '<p>one</p>', 1, 5)")
                s.executeUpdate("INSERT INTO collection (id, name, sort_order, server_id) VALUES (1, 'Server shelf', 1, 100)")
                s.executeUpdate("INSERT INTO collection (id, name, sort_order) VALUES (2, 'Local shelf', 2)")
                s.executeUpdate("INSERT INTO book_collection (book_id, collection_id) VALUES (2, 2)")
                s.execute("PRAGMA user_version = 1")
            }
        }

        val lib = LibraryStore(jdbcLibraryDriver(file), NoSource)
        val grid = lib.libraryFlow().first()
        assertEquals(listOf("Source B"), grid.map { it.title }, "the source novel is untouched")
        assertEquals(1, grid[0].downloadedCount)
        assertEquals(setOf(1), lib.progress(2).readPositions)
        // Both shelves survive: a shelf is the user's, whatever was on it.
        assertEquals(listOf("Server shelf", "Local shelf"),
            lib.collectionsFlow().first().map { it.name })

        lib.syncFolder(FakeFolder(), null, KeyCache())
        val sent = lib.sync.snapshot()
        val keys = sent.map { it.kind to it.key }
        assertTrue(keys.none { it.second.startsWith("srv:") || "\tsrv:" in it.second },
            "nothing names a novel the server used to hold: $keys")
        assertTrue("novel" to "src:rr\tfiction/5" in keys)
        assertTrue(("novel" to "src:rr\tfiction/6") !in keys, "a browsed novel stays private")
        assertTrue("read" to "src:rr\tfiction/5\tfiction/5/1" in keys)
        val local = keys.filter { it.first == "shelf" }.single().second
        assertTrue(local.endsWith("\tsrc:rr\tfiction/5") && !local.startsWith("srv:"), local)
        assertTrue(sent.all { it.ts == LibrarySyncEnrollTs }, "enrolled at the enrollment clock")
        file.delete()
    }

    /** A 1.0 library (schema 4) gains folder sync's state and keeps its records,
     *  so the first snapshot it writes carries everything the old server gave it. */
    @Test
    fun aVersionFourLibraryKeepsItsRecordsForFolderSync() = runBlocking<Unit> {
        val file = Files.createTempFile("library-v4", ".db").toFile()
        File("src/jvmSharedMain/sqldelight/databases/4.db").copyTo(file, overwrite = true)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.executeUpdate("INSERT INTO sync_state (id, device, cursor, clock, enrolled) VALUES (1, 'dev-a', 42, 900, 1)")
                s.executeUpdate("INSERT INTO sync_meta (kind, key, value, ts, device) VALUES ('rating', 'src:rr\tfiction/5', '{\"rating\":4}', 800, 'dev-b')")
                s.execute("PRAGMA user_version = 4")
            }
        }

        val lib = LibraryStore(jdbcLibraryDriver(file), NoSource)
        assertEquals("dev-a", lib.sync.device())
        assertEquals(0L, lib.sync.outSeq())
        assertEquals(0L, lib.sync.snapSeq())
        assertTrue(lib.sync.peerCursors().isEmpty())
        val snap = lib.sync.snapshot().single()
        assertEquals(listOf("rating", "src:rr\tfiction/5", "dev-b"), listOf(snap.kind, snap.key, snap.device))
        assertEquals(800L, snap.ts)
        file.delete()
    }
}

private const val LibrarySyncEnrollTs = 2L
