package com.novelscraper.app.tts

import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.ChapterItem
import com.novelscraper.app.extensions.SourceNovel
import com.novelscraper.app.library.LibraryStore
import com.novelscraper.app.library.SourceOrigin
import com.novelscraper.app.platform.settingsStore
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Where a novel's expressive settings are kept: by the novel, not its row. */
class NovelVoicingsTest {

    private class Source : SourceOrigin {
        override suspend fun name(pluginId: String) = "Fake Source"
        override suspend fun novel(pluginId: String, path: String) = SourceNovel(
            name = "The Novel", path = path, chapters = listOf(ChapterItem("Chapter 1", "$path/c1")),
        )
        override suspend fun chapter(pluginId: String, path: String) = "<p>$path</p>"
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    private lateinit var lib: LibraryStore

    @BeforeTest fun open() {
        lib = LibraryStore(jdbcLibraryDriver(null), Source())
        NovelVoicings.attach(lib)
    }

    @AfterTest fun close() = NovelVoicings.detach()

    private suspend fun add(path: String): Int {
        val id = lib.openSource("fake", path, "The Novel", null)
        lib.refresh(id)
        lib.setInLibrary(id, true)
        return id
    }

    @Test
    fun settingsOutliveRemovingTheNovel() = runBlocking<Unit> {
        val path = "kept-${System.nanoTime()}"
        val first = add(path)
        NovelVoicings.set(first, NovelVoicing(enabled = true, kokoroDialogue = 3))
        NovelVoicings.setTerms(first, listOf(NarrationText.Term("Kael", "Kale")))
        lib.delete(first)

        val again = add(path)
        assertNotEquals(first, again, "a new row")
        assertEquals(NovelVoicing(enabled = true, kokoroDialogue = 3), NovelVoicings.get(again))
        assertEquals(listOf("Kael"), NovelVoicings.terms(again).map { it.from })
    }

    @Test
    fun settingsKeptByIdMoveToTheNovel() = runBlocking<Unit> {
        val id = add("old-${System.nanoTime()}")
        val store = settingsStore("expressive")
        store.putString("book_$id", """{"enabled":true}""")
        store.putString("terms_$id", """[{"from":"Elara","to":"Eh-lara"}]""")

        assertEquals(true, NovelVoicings.get(id).enabled)
        assertEquals(listOf("Elara"), NovelVoicings.terms(id).map { it.from })
        assertEquals(null, store.getString("book_$id", null), "the old entry is gone")
        assertEquals(null, store.getString("terms_$id", null))
    }

    @Test
    fun aSyncedChangeIsWhatTheNovelReads() = runBlocking<Unit> {
        val id = add("synced-${System.nanoTime()}")
        NovelVoicings.received(lib.syncKeyOf(id)!!, """{"voicing":{"kokoroDialogue":7},"terms":[]}""")
        assertEquals(7, NovelVoicings.get(id).kokoroDialogue)
        assertEquals(mapOf(lib.syncKeyOf(id)!! to """{"voicing":{"kokoroDialogue":7}}"""),
            NovelVoicings.all().filterKeys { it == lib.syncKeyOf(id) })
    }
}
