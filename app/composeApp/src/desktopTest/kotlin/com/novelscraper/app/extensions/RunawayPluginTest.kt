package com.novelscraper.app.extensions

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A plugin that will not stop on its own. */
class RunawayPluginTest {
    private val env = PluginEnvironment(
        OkHttpClient(), "TestAgent/1.0", { mutableMapOf() }, allowPrivateHosts = true,
    )

    // Bounded rather than `while (true)`: if the interrupt ever stops working,
    // this test should fail, not wedge the suite for good.
    private val spinner = """
        class P {
          id = 'spin'; name = 'Spin'; site = 'http://example.invalid/'; version = '1.0.0';
          async popularNovels(page) {
            if (page === 1) { const end = Date.now() + 30000; while (Date.now() < end) {} }
            return [{ name: 'answered', path: 'p' }];
          }
          async searchNovels(term, page) { return []; }
          async parseNovel(path) { return { name: 'n', path, chapters: [] }; }
          async parseChapter(path) { return ''; }
        }
        exports.default = new P();
    """.trimIndent()

    @Test fun aSpinningPluginIsStopped() = runBlocking<Unit> {
        val previous = PluginRuntime.callTimeoutMs
        PluginRuntime.callTimeoutMs = 2_000
        try {
            PluginRuntime.load("spin", spinner, env).use { p ->
                val started = System.currentTimeMillis()
                assertFailsWith<Exception> { p.popular(1) }
                val waited = System.currentTimeMillis() - started
                assertTrue(waited < 15_000, "gave up on the plugin rather than waiting out its loop, in ${waited}ms")

                // The discriminator. The plugin's loop still had ~28s to run, so
                // if the interpreter had merely been abandoned rather than
                // stopped, this call would queue behind it and time out too.
                // Answering at once means the interrupt reached the interpreter,
                // and that the context survived it.
                val again = System.currentTimeMillis()
                val answered = p.popular(2).single().name
                val took = System.currentTimeMillis() - again
                assertEquals("answered", answered, "the runtime still works after the interrupt")
                assertTrue(took < 5_000, "and answers at once, so nothing is still spinning: ${took}ms")
            }
        } finally {
            PluginRuntime.callTimeoutMs = previous
        }
    }
}
