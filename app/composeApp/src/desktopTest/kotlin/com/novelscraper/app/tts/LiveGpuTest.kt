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
        assertTrue(KokoroEngine.ensureLoaded(TtsModels.KOKORO), "the model wouldn't load")
        println("on the card: ${GpuVoice.active}, problem: ${GpuVoice.problem}")

        val text = "The lamps were lit early that evening, and the rain had not let up since noon."
        KokoroEngine.generate(text, 0, 1.0f)   // the first one warms the card up
        val t0 = System.nanoTime()
        val pcm = KokoroEngine.generate(text, 0, 1.0f)
        val seconds = pcm.size.toDouble() / KokoroEngine.sampleRate
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("audio ${"%.2f".format(seconds)}s in ${ms}ms (RTF ${"%.2f".format(ms / 1000.0 / seconds)})")
        KokoroEngine.release()
        assertTrue(GpuVoice.active, "narration didn't run on the card")
        assertTrue(seconds > 1.0, "nothing came out")
    }
}
