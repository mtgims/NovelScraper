package com.novelscraper.app.library

import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.SourceNovel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A collection always has a name: a blank one is a tab with nothing on it. */
class CollectionNameTest {
    private class Source : SourceOrigin {
        override suspend fun name(pluginId: String) = "Fake"
        override suspend fun novel(pluginId: String, path: String) = SourceNovel(name = "N", path = path, chapters = emptyList())
        override suspend fun chapter(pluginId: String, path: String) = ""
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    @Test fun aBlankNameIsRefused() = runBlocking<Unit> {
        val lib = LibraryStore(jdbcLibraryDriver(null), Source(), LibraryStoreTest.offlineServer())
        assertFailsWith<IllegalArgumentException> { lib.createCollection("   ") }
        val id = lib.createCollection("Cultivation")
        lib.renameCollection(id, "  ")
        assertEquals(listOf("Cultivation"), lib.collectionsFlow().first().map { it.name })
    }
}
