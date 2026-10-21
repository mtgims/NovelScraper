package com.novelscraper.app.extensions

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Repository indexes and plugin code run on this device, so they are only ever
 * fetched over https (localhost aside, for development).
 */
class RepoFetchTest {
    private val ca = HeldCertificate.Builder().certificateAuthority(0).build()
    private val cert = HeldCertificate.Builder().signedBy(ca).addSubjectAlternativeName("localhost").build()
    private val https = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(cert, ca.certificate).build().sslSocketFactory(), false)
        start()
    }
    private val trusting = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()

    private val index = """[{"id":"x","name":"X","site":"https://x.invalid/","lang":"en","version":"1.0.0","url":"https://x.invalid/x.js"}]"""
    private val plain = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/index.json") { ex ->
            val b = index.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        // A valid index, padded past any sensible size.
        createContext("/huge.json") { ex ->
            val b = index.replace("\"X\"", "\"" + "X".repeat(9 * 1024 * 1024) + "\"").toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        start()
    }
    private val plainBase = "http://127.0.0.1:${plain.address.port}"

    @BeforeTest fun setUp() {
        Extensions.init(OkHttpClient.Builder().sslSocketFactory(trusting.sslSocketFactory(), trusting.trustManager).build())
        Extensions.repos.value.forEach { Extensions.removeRepo(it) }
    }

    @AfterTest fun stop() {
        Extensions.repos.value.forEach { Extensions.removeRepo(it) }
        https.shutdown(); plain.stop(0)
    }

    @Test fun anHttpsRepositoryRedirectedToPlainHttpIsRefused() = runBlocking<Unit> {
        https.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$plainBase/index.json"))
        val repo = https.url("/index.json").toString()
        Extensions.addRepo(repo)
        val listed = Extensions.available()
        assertTrue(repo in listed.failed, "followed https to plain http: ${listed.plugins}")
    }

    @Test fun anOversizedIndexIsRefused() = runBlocking<Unit> {
        val repo = "$plainBase/huge.json"
        Extensions.addRepo(repo)
        val listed = Extensions.available()
        assertTrue(repo in listed.failed, "read a 9 MiB index whole: ${listed.plugins.map { it.id }}")
    }
}
