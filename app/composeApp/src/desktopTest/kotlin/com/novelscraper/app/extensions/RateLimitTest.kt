package com.novelscraper.app.extensions

import com.novelscraper.app.net.SitePacing
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A site that says it is being asked too often is listened to. */
class RateLimitTest {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/busy") { ex ->
            ex.responseHeaders.add("Retry-After", "7")
            ex.sendResponseHeaders(429, -1)
            ex.close()
        }
        createContext("/fine") { ex ->
            val body = "ok".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }

    @AfterTest fun stop() {
        server.stop(0)
        SitePacing.reset()
    }

    private val env = PluginEnvironment(OkHttpClient(), "TestAgent/1.0", { mutableMapOf() }, allowPrivateHosts = true)

    private fun plugin(id: String, path: String) = """
        class P {
          id = '$id'; name = 'P'; site = 'http://127.0.0.1:${server.address.port}'; version = '1.0.0';
          async popularNovels(page) {
            const { fetchApi } = require('@libs/fetch');
            const r = await fetchApi(this.site + '$path');
            return [{ name: String(r.status), path: 'p' }];
          }
          async searchNovels(term, page) { return []; }
          async parseNovel(path) { return { name: 'n', path, chapters: [] }; }
          async parseChapter(path) { return ''; }
        }
        exports.default = new P();
    """.trimIndent()

    @Test fun aTooManyRequestsReplySlowsThatSourceDown() = runBlocking<Unit> {
        PluginRuntime.load("busy", plugin("busy", "/busy"), env).use { p ->
            assertEquals("429", p.popular(1).single().name)
        }
        assertTrue(SitePacing.delayFor("busy") >= 7_000, "waits ${SitePacing.delayFor("busy")} ms")
    }

    @Test fun aSourceThatAnswersKeepsTheUsualPace() = runBlocking<Unit> {
        PluginRuntime.load("fine", plugin("fine", "/fine"), env).use { p ->
            assertEquals("200", p.popular(1).single().name)
        }
        assertEquals(SitePacing.FLOOR_MS, SitePacing.delayFor("fine"))
    }
}
