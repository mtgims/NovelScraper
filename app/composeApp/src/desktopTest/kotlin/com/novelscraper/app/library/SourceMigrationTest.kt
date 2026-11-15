package com.novelscraper.app.library

import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.ChapterItem
import com.novelscraper.app.extensions.NovelItem
import com.novelscraper.app.extensions.SiteChallengeException
import com.novelscraper.app.extensions.SourceNovel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Moving every novel of one source to others at once. */
class SourceMigrationTest {

    /** "old" lists 10 chapters a novel; "a" lists 12; "b" can't give a chapter list at all. */
    private class Sources : SourceOrigin {
        override suspend fun name(pluginId: String) = pluginId.uppercase()
        override suspend fun novel(pluginId: String, path: String): SourceNovel {
            if (pluginId == "b") error("b is down")
            val n = if (pluginId == "old") 10 else 12
            val title = path.split("-").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            return SourceNovel(name = title, path = path, chapters = (1..n).map { ChapterItem("Chapter $it", "$pluginId/$path/$it") })
        }
        override suspend fun chapter(pluginId: String, path: String) = "<p>$path</p>"
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    private val store = LibraryStore(jdbcLibraryDriver(null), Sources(), now = { 1_000_000 })
    private val asked = mutableListOf<String>()

    /** What each target finds for a title. */
    private fun searching(results: Map<String, Map<String, List<String>>>): suspend (String, String) -> List<NovelItem> =
        { id, title ->
            asked += "$id:$title"
            if (id == "checked") throw SiteChallengeException(id, "https://checked.example/")
            results[id]?.get(title).orEmpty().map { NovelItem(name = it, path = "$id-${it.lowercase().replace(' ', '-')}") }
        }

    private fun migration(search: suspend (String, String) -> List<NovelItem>, installed: Set<String> = setOf("old", "a", "b")) =
        SourceMigration(store, MatchFinder(search), installed = { it in installed }, describe = { it.message ?: "failed" }, gapMs = 0)

    private suspend fun onOld(title: String, read: Int = 3): LibBook {
        val id = store.openSource("old", title.lowercase().replace(' ', '-'), title, null)
        store.refresh(id)
        store.setInLibrary(id, true)
        if (read > 0) store.setRead(id, (1..read).toList(), true)
        return store.book(id)!!
    }

    @Test fun theFirstSourceWithTheTitleIsTakenInOrder() = runBlocking<Unit> {
        val finder = MatchFinder(searching(mapOf(
            "a" to mapOf("Shadow Slave" to listOf("Shadow Slave")),
            "b" to mapOf("Shadow Slave" to listOf("Shadow Slave")),
        )))
        val got = finder.find("Shadow Slave", listOf("a", "b"))
        assertEquals("a", got.found?.pluginId)
        assertTrue(got.found!!.exact)
        assertEquals(listOf("a:Shadow Slave"), asked, "b is not asked once a has it")
    }

    @Test fun aNearTitleIsKeptWhileTheRestAreAskedForTheExactOne() = runBlocking<Unit> {
        val near = MatchFinder(searching(mapOf("a" to mapOf("Lord of the Mysteries" to listOf("Lord of Mysteries")))))
            .find("Lord of the Mysteries", listOf("a", "b"))
        assertEquals("a", near.found?.pluginId)
        assertFalse(near.found!!.exact)
        assertEquals(2, asked.size, "b was asked for the exact title")

        asked.clear()
        val exact = MatchFinder(searching(mapOf(
            "a" to mapOf("Lord of the Mysteries" to listOf("Lord of Mysteries")),
            "b" to mapOf("Lord of the Mysteries" to listOf("Lord of the Mysteries 2", "Lord of the Mysteries")),
        ))).find("Lord of the Mysteries", listOf("a", "b"))
        assertEquals("b", exact.found?.pluginId)
        assertEquals("Lord of the Mysteries", exact.found?.item?.name)
    }

    @Test fun aSourceBehindACheckIsReportedAndSkipped() = runBlocking<Unit> {
        val got = MatchFinder(searching(mapOf("a" to mapOf("X Novel" to listOf("X Novel")))))
            .find("X Novel", listOf("checked", "a"))
        assertEquals("a", got.found?.pluginId)
        assertEquals(listOf("checked"), got.unavailable.map { it.pluginId })
        assertTrue(got.unavailable.single().check)
    }

    @Test fun novelsWhoseMatchCantBeFetchedStayWhereTheyAre() = runBlocking<Unit> {
        val one = onOld("One")
        val two = onOld("Two")
        val three = onOld("Three")
        val m = migration(searching(mapOf(
            "a" to mapOf("One" to listOf("One"), "Two" to listOf("Two")),
            "b" to mapOf("Three" to listOf("Three")),
        )))
        val prepared = listOf(one, two, three).associate { it.id to m.prepare(it, listOf("a", "b")).prepared }
        val ready1 = assertIs<SourceMigration.Prepared.Ready>(prepared[one.id])
        assertTrue(ready1.tick, "an exact title with its chapters here")
        assertEquals(3, ready1.preview.readThere)
        assertIs<SourceMigration.Prepared.Failed>(prepared[three.id], "b gave no chapter list")

        for (p in prepared.values.filterIsInstance<SourceMigration.Prepared.Ready>()) {
            m.move(p.fromId, p.toId, keepOld = false, downloadAgain = false)
        }
        val lib = store.libraryFlow().first()
        assertEquals(listOf("a", "a", "old"), lib.map { it.pluginId }.sortedBy { it })
        assertEquals(listOf("Three"), lib.filter { it.pluginId == "old" }.map { it.title }, "Three stayed on old")
        assertEquals(setOf(1, 2, 3), store.progress(ready1.toId).readPositions)
    }

    @Test fun aNearMatchIsOfferedUnticked() = runBlocking<Unit> {
        val book = onOld("Release That Witch")
        val p = migration(searching(mapOf("a" to mapOf("Release That Witch" to listOf("Release the Witch")))))
            .prepare(book, listOf("a")).prepared
        val ready = assertIs<SourceMigration.Prepared.Ready>(p)
        assertFalse(ready.tick)
        assertNotNull(ready.note)
    }

    @Test fun aNovelNeverLoadedHereIsLoadedFromItsSourceFirst() = runBlocking<Unit> {
        // Arrived by sync and never opened: no chapter list on this device yet.
        val id = store.openSource("old", "synced", "Synced", null).also { store.setInLibrary(it, true) }
        assertEquals(0, store.book(id)!!.chapterCount)
        val p = migration(searching(mapOf("a" to mapOf("Synced" to listOf("Synced")))))
            .prepare(store.book(id)!!, listOf("a")).prepared
        val ready = assertIs<SourceMigration.Prepared.Ready>(p)
        assertEquals(10, ready.preview.chaptersHere, "its own source was asked for the list")
        assertTrue(ready.tick)
    }

    @Test fun aNovelNeverLoadedWhoseSourceIsGoneSaysWhatCantMove() = runBlocking<Unit> {
        val id = store.openSource("old", "synced", "Synced", null).also { store.setInLibrary(it, true) }
        val p = migration(searching(mapOf("a" to mapOf("Synced" to listOf("Synced")))), installed = setOf("a"))
            .prepare(store.book(id)!!, listOf("a")).prepared
        val ready = assertIs<SourceMigration.Prepared.Ready>(p)
        assertEquals(0, ready.preview.chaptersHere)
        assertFalse(ready.tick, "what was read can't be matched, so it isn't moved without asking")
        assertNotNull(ready.note)
    }

    @Test fun nothingFoundIsSaid() = runBlocking<Unit> {
        val book = onOld("Nowhere")
        val p = migration(searching(emptyMap())).prepare(book, listOf("a", "b")).prepared
        assertIs<SourceMigration.Prepared.NoMatch>(p)
        assertNull(store.libraryFlow().first().firstOrNull { it.pluginId != "old" })
    }
}
