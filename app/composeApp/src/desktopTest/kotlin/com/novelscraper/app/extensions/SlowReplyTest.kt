package com.novelscraper.app.extensions

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A reply that trickles in must not outlive the call that asked for it. */
class SlowReplyTest {
    /** When the server last managed to send a byte, and when it found the line closed. */
    private val lastSent = AtomicLong()
    private val closedAt = AtomicLong()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/drip") { ex ->
            ex.sendResponseHeaders(200, 0)
            try {
                ex.responseBody.use { out ->
                    repeat(120) {                       // two minutes, a byte a second
                        out.write('x'.code); out.flush()
                        lastSent.set(System.currentTimeMillis())
                        Thread.sleep(1_000)
                    }
                }
            } catch (_: java.io.IOException) {
                closedAt.set(System.currentTimeMillis())
            }
        }
        executor = java.util.concurrent.Executors.newCachedThreadPool()
        start()
    }

    @AfterTest fun stop() = server.stop(0)

    private val env = PluginEnvironment(OkHttpClient(), "TestAgent/1.0", { mutableMapOf() }, allowPrivateHosts = true)

    @Test fun aCallThatGivesUpClosesTheConnectionToo() = runBlocking<Unit> {
        val url = "http://127.0.0.1:${server.address.port}/drip"
        val dripper = """
            class D {
              id = 'drip'; name = 'Drip'; site = '$url'; version = '1.0.0';
              async popularNovels(page) {
                const { fetchApi } = require('@libs/fetch');
                const r = await fetchApi('$url');
                return [{ name: await r.text(), path: 'p' }];
              }
              async searchNovels(term, page) { return []; }
              async parseNovel(path) { return { name: 'n', path, chapters: [] }; }
              async parseChapter(path) { return ''; }
            }
            exports.default = new D();
        """.trimIndent()
        val previous = PluginRuntime.callTimeoutMs
        PluginRuntime.callTimeoutMs = 2_000
        try {
            PluginRuntime.load("drip", dripper, env).use { p ->
                assertFailsWith<Exception> { p.popular(1) }
                val gaveUp = System.currentTimeMillis()
                Thread.sleep(5_000)
                val stillSending = lastSent.get() - gaveUp
                assertTrue(closedAt.get() != 0L && stillSending < 2_000,
                    "the reply kept coming ${stillSending}ms after the call gave up; closed: ${closedAt.get() != 0L}")
            }
        } finally {
            PluginRuntime.callTimeoutMs = previous
        }
    }
}
