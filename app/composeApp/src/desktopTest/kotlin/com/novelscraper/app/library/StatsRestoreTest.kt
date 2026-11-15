package com.novelscraper.app.library

import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.ChapterItem
import com.novelscraper.app.extensions.SourceNovel
import com.novelscraper.app.platform.PropertiesStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Putting a reading-progress backup back into the library. */
class StatsRestoreTest {

    // --- reading the files -------------------------------------------------------

    @Test
    fun readsTheAppsJson() {
        val text = """
            {"total_books": 2, "books_started": 1, "books_finished": 0, "total_chapters": 30,
             "chapters_read": 4, "total_words": 0, "words_read": 0, "hours_read": 0.0,
             "hours_total": 0.0, "hours_remaining": 0.0, "percent_read": 13.3,
             "books": [
               {"book_id": 3, "title": "The Wandering Inn", "total_chapters": 10, "read_count": 4, "percent_read": 40.0},
               {"book_id": 5, "title": "Mother of Learning", "total_chapters": 20, "read_count": 0, "percent_read": 0.0}
             ]}
        """
        assertEquals(
            listOf(BackupNovel("The Wandering Inn", 4, 10), BackupNovel("Mother of Learning", 0, 20)),
            ProgressBackup.parse(text),
        )
    }

    @Test
    fun readsTheAppsCsv() {
        val text = "title,total_chapters,read_chapters,percent_read\n" +
            "\"Lord of the Mysteries, Book 1\",1400,250,17.9\n" +
            "\"A \"\"Quoted\"\" Title\",12,12,100.0\n"
        assertEquals(
            listOf(BackupNovel("Lord of the Mysteries, Book 1", 250, 1400), BackupNovel("A \"Quoted\" Title", 12, 12)),
            ProgressBackup.parse(text),
        )
    }

    @Test
    fun readsTheServersJson() {
        val text = """[
            {"title": "Shadow Slave", "author": "Guiltythree", "site": "Novel Fire", "current_position": 41,
             "current_chapter": "Chapter 41", "chapters_read": 40, "total_chapters": 2000,
             "percent_read": 2.0, "updated_at": "2026-09-01T10:00:00"}
        ]"""
        assertEquals(
            listOf(BackupNovel("Shadow Slave", 40, 2000, author = "Guiltythree", site = "Novel Fire", position = 41)),
            ProgressBackup.parse(text),
        )
    }

    @Test
    fun readsTheServersCsv() {
        // The server's CSV, byte order mark and Windows line ends as Excel saves it again.
        val text = "\uFEFFTitle,Author,Site,Current chapter #,Current chapter,Chapters read,Total chapters,Percent read,Updated\r\n" +
            "Shadow Slave,Guiltythree,Novel Fire,41,\"Chapter 41: Dark, and Deep\",40,2000,2.0,2026-09-01T10:00:00\r\n"
        assertEquals(
            listOf(BackupNovel("Shadow Slave", 40, 2000, author = "Guiltythree", site = "Novel Fire", position = 41)),
            ProgressBackup.parse(text),
        )
    }

    @Test
    fun anythingElseIsSaidSo() {
        assertFailsWith<ProgressBackup.Unreadable> { ProgressBackup.parse("") }
        assertFailsWith<ProgressBackup.Unreadable> { ProgressBackup.parse("{\"voices\": []}") }
        assertFailsWith<ProgressBackup.Unreadable> { ProgressBackup.parse("name,colour\nsky,blue\n") }
        assertFailsWith<ProgressBackup.Unreadable> { ProgressBackup.parse("{ not json") }
    }

    // --- restoring -----------------------------------------------------------------

    private class Source(val chapters: Int = 10) : SourceOrigin {
        override suspend fun name(pluginId: String) = "Royal Road"
        override suspend fun novel(pluginId: String, path: String) = SourceNovel(
            name = path, path = path, chapters = (1..chapters).map { ChapterItem("Chapter $it", "$path/c$it") },
        )
        override suspend fun chapter(pluginId: String, path: String) = "<p>$path</p>"
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    private val storeFile = File(Files.createTempDirectory("ns-restore").toFile(), "stats-restore.properties")
    private val lib = LibraryStore(jdbcLibraryDriver(null), Source())
    private val restore = StatsRestore(lib, PropertiesStore(storeFile))

    /** What a later start would find (saves are written in the background). */
    private fun reopened(): StatsRestore { PropertiesStore.flush(); return StatsRestore(lib, PropertiesStore(storeFile)) }

    /** A novel in the library, titled [title] (the fake source names it after its path). */
    private suspend fun inLibrary(title: String): Int {
        val id = opened(title)
        lib.setInLibrary(id, true)
        return id
    }

    private suspend fun opened(title: String): Int {
        val id = lib.openSource("fake", title, title, null)
        lib.refresh(id)
        return id
    }

    @Test
    fun aNovelInTheLibraryGetsItsChaptersBack() = runBlocking<Unit> {
        val id = inLibrary("The Wandering Inn")
        val outcome = restore.import(listOf(BackupNovel("wandering inn", read = 4, total = 10)))

        assertEquals(StatsRestore.Outcome(restored = 1, waiting = 0), outcome)
        val p = lib.progress(id)
        assertEquals(setOf(1, 2, 3, 4), p.readPositions)
        assertEquals(5, p.lastPosition, "reading carries on at the next chapter")
    }

    @Test
    fun nothingReadIsUnmarkedAndAPlaceInTheNovelIsKept() = runBlocking<Unit> {
        val id = inLibrary("Beware of Chicken")
        lib.setRead(id, listOf(7), true)
        lib.saveProgress(id, 8, 0.5f, null)
        restore.import(listOf(BackupNovel("Beware of Chicken", read = 3)))

        val p = lib.progress(id)
        assertEquals(setOf(1, 2, 3, 7), p.readPositions)
        assertEquals(8, p.lastPosition)
    }

    @Test
    fun theServersPlaceIsUsedAndMoreThanTheNovelHasMeansAll() = runBlocking<Unit> {
        val a = inLibrary("Shadow Slave")
        val b = inLibrary("Mother of Learning")
        restore.import(listOf(
            BackupNovel("Shadow Slave", read = 3, site = "Novel Fire", position = 6),
            BackupNovel("Mother of Learning", read = 50),
        ))
        assertEquals(6, lib.progress(a).lastPosition)
        assertEquals((1..10).toSet(), lib.progress(b).readPositions)
        assertEquals(10, lib.progress(b).lastPosition)
    }

    @Test
    fun aNovelNotInTheLibraryWaitsUntilItIsAdded() = runBlocking<Unit> {
        val outcome = restore.import(listOf(
            BackupNovel("Super Supportive", read = 2),
            BackupNovel("Never Read", read = 0),
        ))
        assertEquals(StatsRestore.Outcome(restored = 0, waiting = 1), outcome, "nothing read, nothing to keep")
        // Kept for the next start.
        assertEquals(listOf("Super Supportive"), reopened().waiting.value.map { it.title })

        val id = inLibrary("Super Supportive")
        val done = restore.matchWaiting(lib.libraryFlowOnce())
        assertEquals(listOf("Super Supportive"), done.map { it.title })
        assertEquals(setOf(1, 2), lib.progress(id).readPositions)
        assertTrue(restore.waiting.value.isEmpty())
    }

    @Test
    fun aNovelFoundFromTheListJoinsTheLibraryWithItsProgress() = runBlocking<Unit> {
        restore.import(listOf(BackupNovel("Azarinth Healer", read = 5)))
        val waiting = restore.waiting.value.single()

        // Found under another title on the source: chosen by hand, so it counts.
        val id = lib.openSource("fake", "Azarinth Healer (Book 1)", "Azarinth Healer (Book 1)", null)
        restore.restoreFound(id, waiting)

        assertTrue(lib.libraryFlowOnce().any { it.id == id }, "in the library")
        assertEquals((1..5).toSet(), lib.progress(id).readPositions)
        assertTrue(restore.waiting.value.isEmpty())
    }

    @Test
    fun theServersChapterOneMeansNoPlace() = runBlocking<Unit> {
        // The server said 1 for a novel never opened, read or not.
        val id = inLibrary("Reverend Insanity")
        restore.import(listOf(BackupNovel("Reverend Insanity", read = 5, position = 1)))
        assertEquals(6, lib.progress(id).lastPosition)
    }

    @Test
    fun aNovelTheSourceListsNoChaptersForStaysOut() = runBlocking<Unit> {
        val empty = LibraryStore(jdbcLibraryDriver(null), Source(chapters = 0))
        val r = StatsRestore(empty, PropertiesStore(File(storeFile.parentFile, "empty.properties")))
        r.import(listOf(BackupNovel("Gone Quiet", read = 3)))
        val id = empty.openSource("fake", "Gone Quiet", "Gone Quiet", null)

        assertFailsWith<IllegalStateException> { r.restoreFound(id, r.waiting.value.single()) }
        assertTrue(empty.libraryFlowOnce().isEmpty(), "not added without chapters")
        assertEquals(listOf("Gone Quiet"), r.waiting.value.map { it.title }, "still waiting")
    }

    @Test
    fun aSecondPickAfterTheNovelCameBackDoesNothing() = runBlocking<Unit> {
        restore.import(listOf(BackupNovel("Release That Witch", read = 2)))
        val n = restore.waiting.value.single()
        val first = opened("Release That Witch")
        val second = opened("Release That Witch (Another Site)")
        assertTrue(restore.restoreFound(first, n))
        assertEquals(false, restore.restoreFound(second, n))
        assertEquals(listOf(first), lib.libraryFlowOnce().map { it.id })
    }

    @Test
    fun importingAgainKeepsTheFurthestAndForgettingClearsTheList() = runBlocking<Unit> {
        restore.import(listOf(BackupNovel("Defiance of the Fall", read = 10)))
        restore.import(listOf(BackupNovel("Defiance of the Fall", read = 30), BackupNovel("Primal Hunter", read = 1)))
        assertEquals(mapOf("Defiance of the Fall" to 30, "Primal Hunter" to 1),
            restore.waiting.value.associate { it.title to it.read })

        restore.forget(restore.waiting.value.first { it.title == "Primal Hunter" })
        assertEquals(listOf("Defiance of the Fall"), restore.waiting.value.map { it.title })
        restore.forgetAll()
        assertTrue(reopened().waiting.value.isEmpty())
    }
}
