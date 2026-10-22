package com.novelscraper.app.tts

import com.sun.net.httpserver.HttpServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A voice model is a program of sorts the app runs: only the published one is installed. */
class ModelDownloadTest {
    private val voice = "en_US-amy-medium"

    /** A well-formed model archive holding what a Piper voice needs, but not the real one. */
    private val impostor: ByteArray = ByteArrayOutputStream().also { out ->
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            for ((name, body) in listOf(
                "vits-piper-$voice/$voice.onnx" to "not a model",
                "vits-piper-$voice/tokens.txt" to "a 1",
                "vits-piper-$voice/espeak-ng-data/phontab" to "x",
            )) {
                val b = body.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = b.size.toLong() })
                tar.write(b); tar.closeArchiveEntry()
            }
        }
    }.toByteArray()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex -> ex.sendResponseHeaders(200, impostor.size.toLong()); ex.responseBody.use { it.write(impostor) } }
        start()
    }

    @AfterTest fun stop() { server.stop(0); TtsModels.modelDir(voice).deleteRecursively() }

    /** Every request goes to the local server instead, as a tampered mirror would answer. */
    private val tampered = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val local = chain.request().url.newBuilder().scheme("http").host("127.0.0.1").port(server.address.port).build()
        chain.proceed(chain.request().newBuilder().url(local).build())
    }).build()

    @Test fun anArchiveThatIsNotThePublishedOneIsNotInstalled() {
        var outcome: KokoroDownloader.Progress? = null
        KokoroDownloader.download(tampered, voice) { outcome = it }
        assertTrue(outcome is KokoroDownloader.Progress.Failed, "the impostor was accepted: $outcome")
        assertTrue("checksum" in (outcome as KokoroDownloader.Progress.Failed).message, "failed, but why: $outcome")
        assertFalse(TtsModels.isModelReady(voice), "and nothing was installed")
    }

    /** The real archive passes its pinned checksum. Downloads 67 MB, so only
     *  when asked: `-PliveTts=true`. */
    @Test fun thePublishedArchiveIsInstalled() {
        if (System.getProperty("live.tts") != "true") return
        var outcome: KokoroDownloader.Progress? = null
        KokoroDownloader.download(OkHttpClient(), voice) { outcome = it }
        assertTrue(outcome is KokoroDownloader.Progress.Done, "the real voice: $outcome")
        assertTrue(TtsModels.isModelReady(voice))
    }
}
