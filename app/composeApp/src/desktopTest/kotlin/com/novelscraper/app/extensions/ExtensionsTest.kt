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
import kotlin.test.assertTrue

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

    /**
     * Loading runs the plugin's own top-level code. Nothing bounded it, and it
     * ran under the lock every source takes, so one plugin looping as it loaded
     * stopped every other source from starting.
     */
    @Test
    fun aPluginLoopingAsItLoadsHoldsUpNoOtherSource() = runBlocking<Unit> {
        val previous = PluginRuntime.loadTimeoutMs
        PluginRuntime.loadTimeoutMs = 2_000
        try {
            place("spinload", plugin("spinload", topLevel = spin(30_000)))
            place("quick", plugin("quick"))
            val stuck = async(Dispatchers.Default) { runCatching { Extensions.runtime("spinload") } }
            delay(300)                          // the looping one is loading now

            val started = System.currentTimeMillis()
            val quick = Extensions.runtime("quick")
            val waited = System.currentTimeMillis() - started
            assertTrue(waited < 1_500, "another source started at once, not behind the loop: ${waited}ms")
            assertEquals("answered", quick.popular(1).single().name)

            val gaveUp = System.currentTimeMillis()
            assertTrue(stuck.await().isFailure, "the looping one fails to load")
            assertTrue(System.currentTimeMillis() - gaveUp < 10_000, "and is given up on, not waited out")
        } finally {
            PluginRuntime.loadTimeoutMs = previous
        }
    }

    /** Loading outside the lock means two callers can load one source at once,
     *  or it can go while loading: either way one runtime, or none, is in use. */
    @Test
    fun aSourceLoadedTwiceAtOnceOrRemovedWhileLoadingIsSettled() = runBlocking<Unit> {
        place("twice", plugin("twice", topLevel = spin(500)))
        val both = (1..2).map { async(Dispatchers.Default) { Extensions.runtime("twice") } }.map { it.await() }
        assertTrue(both[0] === both[1], "both callers got the one runtime in use")
        assertEquals("answered", both[0].popular(1).single().name)

        place("going", plugin("going", topLevel = spin(1_000)))
        val loading = async(Dispatchers.Default) { runCatching { Extensions.runtime("going") } }
        delay(300)
        Extensions.uninstall("going")
        assertTrue(loading.await().exceptionOrNull() is PluginNotInstalledException,
            "uninstalled while it loaded: not put in use")
    }
}
