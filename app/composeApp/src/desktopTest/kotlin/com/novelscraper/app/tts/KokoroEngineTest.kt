package com.novelscraper.app.tts

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KokoroEngineTest {

    private class Fake(val name: String) : TtsModel {
        var released = false
        override val sampleRate = 24000
        override val numSpeakers = 54
        override fun generate(text: String, speaker: Int, speed: Float) = FloatArray(1000) { 0.1f }
        override fun release() { released = true }
    }

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
            Fake(if (built.isEmpty()) "processor" else "card").also { built += it }
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
}
