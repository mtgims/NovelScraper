package com.novelscraper.app.library

import com.novelscraper.app.data.BookCollectionsUpdate
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.BookUpdate
import com.novelscraper.app.data.ChapterListItem
import com.novelscraper.app.data.CollectionRead
import com.novelscraper.app.data.CollectionUpdate
import com.novelscraper.app.data.ProgressUpdate
import com.novelscraper.app.data.ReadingProgressRead
import com.novelscraper.app.data.SyncRequest
import com.novelscraper.app.data.SyncResponse
import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.SourceNovel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A library made by 0.31 (schema 1) opens in this version, keeps everything,
 *  and enrolls in sync with it. */
class LibraryMigrationTest {

    private object NoSource : SourceOrigin {
        override suspend fun name(pluginId: String) = ""
        override suspend fun novel(pluginId: String, path: String) = SourceNovel()
        override suspend fun chapter(pluginId: String, path: String) = ""
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    private class SyncOnly(val store: LibraryStoreTest.FakeSync) : ServerOrigin {
        override val enabled = true
        override suspend fun books() = emptyList<BookRead>()
        override suspend fun book(id: Int): BookRead = error("none")
        override suspend fun collections() = emptyList<CollectionRead>()
        override suspend fun chapters(id: Int) = emptyList<ChapterListItem>()
        override suspend fun chapter(id: Int, position: Int) = ""
        override suspend fun progress(id: Int): ReadingProgressRead = error("none")
        override suspend fun putProgress(id: Int, update: ProgressUpdate) {}
        override suspend fun editBook(id: Int, update: BookUpdate) {}
        override suspend fun setBookCollections(id: Int, update: BookCollectionsUpdate) {}
        override suspend fun updateCollection(id: Int, update: CollectionUpdate) {}
        override suspend fun deleteCollection(id: Int) {}
        override suspend fun deleteBook(id: Int) {}
        override suspend fun sync(request: SyncRequest) = store.sync(request)
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

        val server = LibraryStoreTest.FakeSync()
        val lib = LibraryStore(jdbcLibraryDriver(file), NoSource, SyncOnly(server))
        val grid = lib.libraryFlow().first()
        assertEquals(listOf("Server A", "Source B"), grid.map { it.title }, "nothing lost")
        assertEquals(1, grid[1].downloadedCount)
        assertEquals(setOf(1), lib.progress(2).readPositions)

        lib.syncNow()
        val keys = server.records.keys
        assertTrue("novel" to "srv:10" in keys)
        assertTrue("novel" to "src:rr\tfiction/5" in keys)
        assertTrue(("novel" to "src:rr\tfiction/6") !in keys, "a browsed novel stays private")
        assertTrue("read" to "src:rr\tfiction/5\tfiction/5/1" in keys)
        assertEquals("""{"rating":4}""", server.value("rating", "srv:10"))
        assertTrue("collection" to "srv:100" in keys, "the server shelf keeps its server name")
        val local = keys.filter { it.first == "shelf" }.single().second
        assertTrue(local.endsWith("\tsrc:rr\tfiction/5") && !local.startsWith("srv:"), local)
        assertTrue(server.records.values.all { it.c.ts == LibrarySyncEnrollTs }, "enrolled at the enrollment clock")
        file.delete()
    }
}

private const val LibrarySyncEnrollTs = 2L
