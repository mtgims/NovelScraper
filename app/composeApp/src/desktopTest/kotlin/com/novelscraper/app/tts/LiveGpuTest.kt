package com.novelscraper.app.tts

import com.novelscraper.app.net.Net
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fetches the GPU pack the way Settings does, loads Kokoro through it and
 * speaks. Off by default (about 2 GB, and an NVIDIA card): run with
 * `./gradlew :composeApp:desktopTest --tests "*LiveGpuTest*" -PliveGpu=true`,
 * adding `-PliveGpuModel=<folder of an unpacked Kokoro model>` to skip
 * downloading the voice as well.
 */
class LiveGpuTest {

    @Test
    fun narratesOnTheGraphicsCard() {
        if (System.getProperty("live.gpu") != "true") return
        Net.init()
        println("support: ${GpuVoice.support}")
        assertTrue(GpuVoice.support is GpuVoice.Support.Ready, "no card this pack can use")

        if (!GpuVoice.installed) {
            val watch = Thread {
                while (true) {
                    println("  ${GpuVoice.state.value}")
                    Thread.sleep(15_000)
                }
            }.apply { isDaemon = true; start() }
            val t0 = System.nanoTime()
            GpuVoice.download()
            watch.interrupt()
            println("download and unpack: ${(System.nanoTime() - t0) / 1_000_000_000} s, final state ${GpuVoice.state.value}")
        }
        assertTrue(GpuVoice.installed, "the pack didn't install")
        println("pack on disk: ${GpuVoice.installedBytes / 1_000_000} MB")

        System.getProperty("live.gpu.model")?.takeIf { it.isNotBlank() }?.let { from ->
            val to = TtsModels.modelDir(TtsModels.KOKORO)
            if (!TtsModels.isModelReady(TtsModels.KOKORO)) java.io.File(from).copyRecursively(to, overwrite = true)
        }
        assertTrue(TtsModels.isModelReady(TtsModels.KOKORO), "no Kokoro model (pass -PliveGpuModel)")
        val spec = TtsModels.spec(TtsModels.KOKORO)
        val dir = TtsModels.modelDir(TtsModels.KOKORO).absolutePath

        // While the cards are being tried, narration must neither wait for it
        // nor keep anything else waiting: 0.45.0 tried them inside the engine's
        // lock, and closing the window then froze the app.
        GpuVoice.setEnabled(true)   // as the Settings switch does, which starts the test
        GpuVoice.probeInBackground(spec, dir)
        var since = System.nanoTime()
        assertTrue(KokoroEngine.ensureLoaded(TtsModels.KOKORO), "the model wouldn't load")
        val loadMs = (System.nanoTime() - since) / 1_000_000
        since = System.nanoTime()
        KokoroEngine.release()
        val releaseMs = (System.nanoTime() - since) / 1_000_000
        println("while testing: load ${loadMs}ms on the card=${GpuVoice.onCard}, release ${releaseMs}ms")
        assertTrue(releaseMs < 5_000, "releasing narration waited on the test")

        val deadline = System.currentTimeMillis() + 400_000
        while (GpuVoice.probeResult(spec, dir) == null && System.currentTimeMillis() < deadline) Thread.sleep(1_000)
        println("tested: ${GpuVoice.probeResult(spec, dir)}")
        assertTrue(KokoroEngine.ensureLoaded(TtsModels.KOKORO), "the model wouldn't load")
        println("on the card: ${GpuVoice.onCard}, problem: ${GpuVoice.problem}")

        val text = "The lamps were lit early that evening, and the rain had not let up since noon."
        KokoroEngine.generate(text, 0, 1.0f)   // the first one warms the card up
        val t0 = System.nanoTime()
        val pcm = KokoroEngine.generate(text, 0, 1.0f)
        val seconds = pcm.size.toDouble() / KokoroEngine.sampleRate
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("audio ${"%.2f".format(seconds)}s in ${ms}ms (RTF ${"%.2f".format(ms / 1000.0 / seconds)})")
        KokoroEngine.release()
        assertTrue(GpuVoice.onCard, "narration didn't run on the card")
        assertTrue(seconds > 1.0, "nothing came out")
    }
}
