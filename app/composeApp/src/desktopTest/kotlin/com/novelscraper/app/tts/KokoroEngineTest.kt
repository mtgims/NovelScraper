package com.novelscraper.app.tts

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KokoroEngineTest {

    private class Fake(val name: String, val threads: MutableSet<String>) : TtsModel {
        var released = false
        override val sampleRate = 24000
        override val numSpeakers = 54
        override fun generate(text: String, speaker: Int, speed: Float): FloatArray {
            threads += Thread.currentThread().name
            return FloatArray(1000) { 0.1f }
        }
        override fun release() { threads += Thread.currentThread().name; released = true }
    }

    /** The threads the native model was used on: built, spoken with, released. */
    private val nativeThreads: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    private val built = mutableListOf<Fake>()
    private var failNext = false
    private val dir: File get() = TtsModels.modelDir(TtsModels.KOKORO)

    @BeforeTest
    fun setUp() {
        // The files isModelReady looks for, in the tests' own data folder.
        dir.mkdirs()
        TtsModels.spec(TtsModels.KOKORO).required.forEach { File(dir, it).writeText("") }
        File(dir, "espeak-ng-data").mkdirs()
        KokoroEngine.build = { _, _, _ ->
            if (failNext) { failNext = false; error("no card") }
            nativeThreads += Thread.currentThread().name
            Fake(if (built.isEmpty()) "processor" else "card", nativeThreads).also { built += it }
        }
    }

    @AfterTest
    fun tearDown() {
        KokoroEngine.release()
        KokoroEngine.build = ::buildOfflineTts
        dir.deleteRecursively()
    }

    /** 1.3.3 moved to the card only at the next Listen, so a narration that
     *  started while the card was being tried stayed on the processor. */
    @Test
    fun narrationUnderWayMovesToTheCardOnceItHasBeenTried() {
        assertTrue(KokoroEngine.ensureLoaded(TtsModels.KOKORO))
        KokoroEngine.generate("A first sentence.", 0, 1f)
        assertEquals(1, built.size)

        KokoroEngine.reloadOnNextUse()   // what a passing probe does
        KokoroEngine.generate("The next sentence.", 0, 1f)
        assertEquals(listOf("processor", "card"), built.map { it.name })
        assertTrue(built[0].released)

        KokoroEngine.generate("And the one after.", 0, 1f)
        assertEquals(2, built.size)   // rebuilt once, not every sentence
    }

    @Test
    fun aRebuildThatFailsKeepsTheVoiceThatWasPlaying() {
        assertTrue(KokoroEngine.ensureLoaded(TtsModels.KOKORO))
        failNext = true
        KokoroEngine.reloadOnNextUse()
        val samples = KokoroEngine.generate("Still speaking.", 0, 1f)
        assertTrue(samples.isNotEmpty())
        assertEquals(false, built.single().released)
    }

    /** ONNX Runtime's CUDA provider keeps working memory for every thread that
     *  runs the model, about 1 GB each over time, and never hands it back while
     *  the model lives. Narration generates from a coroutine pool, so each
     *  sentence could land on another worker: on 2026-11-17 the card filled
     *  after 37 minutes, the runtime aborted the app and the driver was left
     *  refusing every new window. Every native call has to happen on one thread. */
    @Test
    fun theModelIsOnlyEverUsedOnOneThread() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            pool.submit<Boolean> { KokoroEngine.ensureLoaded(TtsModels.KOKORO) }.get()
            val jobs = (1..40).map { i -> pool.submit<FloatArray> { KokoroEngine.generate("Sentence $i.", 0, 1f) } }
            jobs.forEach { assertTrue(it.get().isNotEmpty()) }
            KokoroEngine.reloadOnNextUse()
            pool.submit<FloatArray> { KokoroEngine.generate("After a rebuild.", 0, 1f) }.get()
            pool.submit { KokoroEngine.release() }.get()
        } finally {
            pool.shutdownNow()
        }
        assertEquals(2, built.size)
        assertEquals(1, nativeThreads.size, "used on $nativeThreads")
    }
}
