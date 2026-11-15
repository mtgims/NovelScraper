package com.novelscraper.app.extensions

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Telling the reader that installed sources have newer versions. */
class ExtensionUpdatesTest {
    @AfterTest fun clear() = ExtensionUpdates.forget()

    private val repo = "https://repo.example/index.json"
    private val other = "https://other.example/index.json"
    private fun offered(id: String, version: String, from: String = repo) =
        RepoPlugin(id, id, "https://$id.example", "en", version, "https://repo.example/$id.js", repo = from)
    private fun installed(id: String, version: String, from: String = repo) =
        InstalledPlugin(id, id, "https://$id.example", "en", version, repo = from)

    @Test fun onlyNewerVersionsFromTheSameRepositoryCount() {
        val got = ExtensionUpdates.pending(
            listOf(installed("a", "1.0.0"), installed("b", "2.0.0"), installed("c", "1.0.0")),
            listOf(offered("a", "1.0.1"), offered("b", "2.0.0"), offered("c", "9.0.0", from = other)),
        )
        assertEquals(listOf("a"), got.map { it.id }, "b is current, and c's newer one is another repository's")
    }

    @Test fun aRepositoryThatDoesntAnswerKeepsWhatWasKnown() {
        ExtensionUpdates.noteCatalogue(listOf(offered("a", "1.1.0"), offered("x", "2.0.0", from = other)), emptyList())
        ExtensionUpdates.noteCatalogue(listOf(offered("a", "1.2.0")), failed = listOf(other))
        assertEquals(
            setOf("a" to "1.2.0", "x" to "2.0.0"),
            ExtensionUpdates.catalogue.value.map { it.id to it.version }.toSet(),
        )
        ExtensionUpdates.noteCatalogue(listOf(offered("a", "1.2.0")), failed = emptyList())
        assertEquals(listOf("a"), ExtensionUpdates.catalogue.value.map { it.id }, "answered without it: gone")
    }
}
