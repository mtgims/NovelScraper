package com.novelscraper.app.extensions

import com.novelscraper.app.platform.appFilesDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The installed sources as the app runs them: several, at once, coming and going. */
class ExtensionsTest {

    @BeforeTest fun setUp() {
        Extensions.init()
        File(appFilesDir(), "extensions").mkdirs()
    }

    /** Put a plugin's code where an installed one lives. */
    private fun place(id: String, code: String) = File(appFilesDir(), "extensions/$id.js").writeText(code)

    /** A plugin whose popular list runs [popular] first, after [topLevel] runs at load. */
    private fun plugin(id: String, popular: String = "", topLevel: String = "") = """
        $topLevel
        class P {
          id = '$id'; name = '$id'; site = 'http://example.invalid/'; version = '1.0.0';
          async popularNovels(page) { $popular return [{ name: 'answered', path: 'p' }]; }
          async searchNovels(term, page) { return []; }
          async parseNovel(path) { return { name: 'n', path, chapters: [] }; }
          async parseChapter(path) { return ''; }
        }
        exports.default = new P();
    """.trimIndent()

    // Bounded, so a regression fails the test rather than wedging the suite.
    private fun spin(ms: Int) = "{ const end = Date.now() + $ms; while (Date.now() < end) {} }"

    /**
     * Ending an interpreter while it is evaluating cuts the call off ("Already
     * closed"), which is why installing and evicting wait for it to go idle.
     * Uninstalling did not: a source removed mid-download ended the download.
     */
    @Test
    fun uninstallingASourceLetsACallUnderWayFinish() = runBlocking<Unit> {
        place("busy", plugin("busy", popular = spin(3_000)))
        val rt = Extensions.runtime("busy")
        val call = async(Dispatchers.Default) { runCatching { rt.popular(1) } }
        delay(500)                              // well inside the call
        Extensions.uninstall("busy")
        val result = call.await()               // finished, not cut off
        assertEquals("answered", result.getOrThrow().single().name, "the call finished normally")
        assertFailsWith<PluginNotInstalledException> { Extensions.runtime("busy") }
    }
}
