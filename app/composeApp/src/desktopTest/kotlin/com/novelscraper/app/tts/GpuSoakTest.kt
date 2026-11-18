package com.novelscraper.app.tts

import com.novelscraper.app.net.Net
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Narrates a file of sentences (one per line) on the graphics card and prints
 * the card's memory every 25, stopping at 6 GB so a leak can't fill the card
 * and wedge the driver. Off by default:
 * `-PliveGpuSoak=<sentences.txt> [-PliveGpuSoakRounds=3] [-PliveGpuSoakThreads=10]`,
 * the last calling from a new long-lived thread every N sentences, as the
 * coroutine pool does. Needs the CUDA pack and Kokoro in the tests' own data
 * folder (`build/test-home/data/novelscraper`); KokoroEngineTest deletes that
 * Kokoro copy when it finishes, so copy it in again after a full run.
 * Measured 2026-11-18: a flat 2.2 GB on one thread or many; before the
 * engine had its own thread, about 1 GB more every few new threads.
 */
class GpuSoakTest {
    private fun vram(): Pair<Int, Int> {
        val me = ProcessHandle.current().pid().toString()
        val apps = ProcessBuilder("nvidia-smi", "--query-compute-apps=pid,used_memory", "--format=csv,noheader,nounits")
            .start().inputStream.bufferedReader().readText()
        val mine = apps.lines().mapNotNull { l -> l.split(",").map { it.trim() }.takeIf { it.size == 2 && it[0] == me }?.get(1)?.toIntOrNull() }.sum()
        val total = ProcessBuilder("nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits")
            .start().inputStream.bufferedReader().readText().trim().toInt()
        return mine to total
    }

    @Test
    fun soak() {
        val file = System.getProperty("live.gpu.soak") ?: return
        if (file.isBlank()) return
        val rounds = (System.getProperty("live.gpu.soak.rounds") ?: "3").toInt()
        Net.init()
        assertTrue(GpuVoice.installed, "pack not installed")
        val spec = TtsModels.spec(TtsModels.KOKORO)
        val dir = TtsModels.modelDir(TtsModels.KOKORO).absolutePath
        GpuVoice.setEnabled(true)
        GpuVoice.probeInBackground(spec, dir)
        val deadline = System.currentTimeMillis() + 400_000
        while (GpuVoice.probeResult(spec, dir) == null && System.currentTimeMillis() < deadline) Thread.sleep(1_000)
        println("tested: ${GpuVoice.probeResult(spec, dir)}")
        assertTrue(KokoroEngine.ensureLoaded(TtsModels.KOKORO), "load")
        assertTrue(GpuVoice.onCard, "not on the card")
        println("after load: ${vram()}")
        val sentences = File(file).readLines().filter { it.isNotBlank() }
        var n = 0
        var audio = 0.0
        val t0 = System.nanoTime()
        val threadEvery = (System.getProperty("live.gpu.soak.threads") ?: "0").toInt()
        val workers = mutableListOf<java.util.concurrent.ExecutorService>()
        outer@ for (r in 0 until rounds) for (s in sentences) {
            val pcm = if (threadEvery > 0) {
                // a new long-lived thread every [threadEvery] sentences, as a pool's
                // workers stay alive; the old ones are kept, not shut down
                if (n % threadEvery == 0) workers += java.util.concurrent.Executors.newSingleThreadExecutor()
                workers.last().submit<FloatArray> { KokoroEngine.generate(s, n % 3, 1.0f) }.get()
            } else KokoroEngine.generate(s, n % 3, 1.0f)
            audio += pcm.size.toDouble() / KokoroEngine.sampleRate
            n++
            if (n % 25 == 0) {
                val (mine, total) = vram()
                println("n=$n audio=${(audio / 60).toInt()}min len=${s.length} mine=${mine}MiB total=${total}MiB t=${(System.nanoTime() - t0) / 1_000_000_000}s")
                if (mine > 6000 || total > 9000) { println("STOP: memory guard"); break@outer }
            }
        }
        println("final: n=$n ${vram()}")
        KokoroEngine.release()
        Thread.sleep(500)
        println("after release: ${vram()}")
    }
}
