package com.novelscraper.app.net

import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A cover's or an inline image's address comes from a source, which is
 * third-party code: fetching it must not reach this machine or its network.
 */
class ImageClientTest {
    private val hits = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex -> hits.incrementAndGet(); ex.sendResponseHeaders(200, -1); ex.close() }
        start()
    }

    @AfterTest fun stop() = server.stop(0)

    @Test fun anImageOnTheLocalNetworkIsNotFetched() {
        Net.init()
        val local = Request.Builder().url("http://127.0.0.1:${server.address.port}/cover.jpg").build()
        assertFailsWith<java.io.IOException> { imageClient().newCall(local).execute().close() }
        assertEquals(0, hits.get(), "nothing reached the local server")
    }
}
