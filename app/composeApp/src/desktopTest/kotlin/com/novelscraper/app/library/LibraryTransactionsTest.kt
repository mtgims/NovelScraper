package com.novelscraper.app.library

import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.ChapterItem
import com.novelscraper.app.extensions.SourceNovel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Changes made at the same moment from different threads, as the app makes
 * them (a novel's settings going to sync from a background scope while the
 * reader adds or removes a novel). Each one used to be able to fail outright:
 * on the app's library file with "database is locked" (seen in a real log,
 * 2026-11-15), in memory with "cannot start a transaction within a transaction".
 */
class LibraryTransactionsTest {

    private object Source : SourceOrigin {
        override suspend fun name(pluginId: String) = "Fake"
        override suspend fun novel(pluginId: String, path: String) = SourceNovel(
            name = "Novel", path = path, chapters = listOf(ChapterItem("Chapter 1", "$path/c1")),
        )
        override suspend fun chapter(pluginId: String, path: String) = "<p>$path</p>"
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    private fun everyChangeLands(lib: LibraryStore) = runBlocking<Unit> {
        // Single-statement writes alongside: reopening a novel, cancelling its
        // downloads. They hold the same write lock a transaction waits for.
        val opened = lib.openSource("fake", "opened", "Novel", null)
        val alongside = async(Dispatchers.IO) {
            runCatching {
                repeat(200) {
                    lib.openSource("fake", "opened", "Novel", null)
                    lib.cancelDownloads(opened)
                }
            }.exceptionOrNull()
        }
        val failures = (0 until 8).map { t ->
            async(Dispatchers.IO) {
                runCatching {
                    repeat(25) { i ->
                        val id = lib.openSource("fake", "n-$t-$i", "Novel", null)
                        lib.setInLibrary(id, true)
                        lib.noteNovelSettings("src:fake\tn-$t-$i", """{"voicing":{}}""")
                        lib.delete(id)
                    }
                }.exceptionOrNull()
            }
        }.awaitAll().filterNotNull() + listOfNotNull(alongside.await())
        assertEquals(emptyList(), failures.map { "${it::class.simpleName}: ${it.message}" })
        val kept = lib.sync.snapshot().count { it.kind == "voicing" }
        assertEquals(8 * 25, kept, "every novel's settings recorded")
    }

    @Test
    fun onTheLibraryFile() {
        val file = File(Files.createTempDirectory("ns-tx").toFile(), "library.db")
        everyChangeLands(LibraryStore(jdbcLibraryDriver(file), Source))
    }

    @Test
    fun inMemory() = everyChangeLands(LibraryStore(jdbcLibraryDriver(null), Source))
}
