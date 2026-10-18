package com.novelscraper.app.tts

import com.novelscraper.app.data.ReaderPrefs
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Save as audio reads a chapter the way narration does. */
class AudiobookExportTest {

    private val content = "<p>\"Run,\" she said. Nobody moved.</p><p>The end.</p>"

    /** Every piece is 100 frames of tone; records what it was asked to say. */
    private val voice = object : NarratorVoice {
        val said = ArrayList<Pair<String, Int>>()
        override fun prepare(modelId: String): String? = null
        override val sampleRate = 1000
        override fun generate(text: String, speaker: Int, speed: Float): FloatArray {
            said.add(text to speaker)
            return FloatArray(100) { if (it % 2 == 0) 0.4f else -0.4f }
        }
        override fun release() {}
    }

    private val director = object : NarrationDirector {
        var on = true
        override fun enabled(bookId: Int) = on
        override fun direct(bookId: Int, spoken: List<String?>, paragraphEnd: BooleanArray) =
            ExpressiveReading.direct(spoken, paragraphEnd)
        override fun speakerFor(bookId: Int, engine: String, narrator: Int, role: Role) =
            if (role == Role.DIALOGUE) narrator + 7 else narrator
        override fun pronunciations(bookId: Int) = listOf(NarrationText.Term("Nobody", "No one"))
    }

    @BeforeTest fun prefs() {
        ReaderPrefs.init()
        ReaderPrefs.setTtsEngine(ReaderPrefs.ENGINE_PIPER)
        ReaderPrefs.setTtsRate(1f)
        ReaderPrefs.setTtsSkipJunk(true)
        ReaderPrefs.setTtsJunkPatterns(emptyList())
        ReaderPrefs.setTtsDictionary(emptyList())
    }

    private fun render(director: NarrationDirector?) = runBlocking {
        voice.said.clear()
        AudiobookExport.chapterAudio(1, content, voice, director).map { it() }
    }

    @Test fun withoutExpressiveReadingItReadsAsItAlwaysDid() {
        val plain = render(null)
        assertEquals(listOf("\"Run,\" she said.", "Nobody moved.", "The end."), voice.said.map { it.first })
        assertEquals(listOf(100, 100, 100), plain.map { it.size }, "one plain piece per sentence")

        director.on = false
        val off = render(director)
        assertEquals("No one moved.", voice.said[1].first, "the novel's own words even so")
        assertEquals(listOf(100, 100, 100), off.map { it.size })
    }

    @Test fun withExpressiveReadingItSoundsLikeNarration() {
        director.on = true
        val audio = render(director)
        assertEquals(3, audio.size, "still one piece per sentence")
        val speaker = ReaderPrefs.speakerFor(ReaderPrefs.ENGINE_PIPER)
        assertEquals(listOf(speaker + 7, speaker, speaker, speaker), voice.said.map { it.second },
            "the line in the dialogue voice, the rest the narrator's")
        assertEquals("No one moved.", voice.said[2].first)
        // Pauses are directed. The fake voice leaves no silence of its own, so each
        // piece is its 100 frames and each gap the pause, less the lead-in the
        // next sentence is assumed to bring.
        val lead = DirectedSpeech.LEAD_PAD_MS
        assertEquals(
            listOf(
                100 + ExpressiveReading.PAUSE_JOIN + 100 + ExpressiveReading.PAUSE_SENTENCE - lead,
                100 + ExpressiveReading.PAUSE_PARAGRAPH - lead,
                100 + ExpressiveReading.PAUSE_PARAGRAPH - lead,
            ),
            audio.map { it.size },
        )
    }
}
