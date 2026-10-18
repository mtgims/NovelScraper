package com.novelscraper.app.tts

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Directed speech: trimmed silence, exact pauses, voices at one loudness. */
class DirectedSpeechTest {

    private val rate = 1000   // Hz: one frame a millisecond

    /** 100 ms of silence, [voiced] ms of tone at [level], 100 ms of silence. */
    private fun padded(level: Float, voiced: Int = 100) =
        FloatArray(200 + voiced) { if (it in 100 until 100 + voiced) (if (it % 2 == 0) level else -level) else 0f }

    /** A voice whose loudness is chosen per speaker. */
    private inner class Voice(val levels: Map<Int, Float>) : NarratorVoice {
        val spoken = ArrayList<Pair<String, Int>>()
        override fun prepare(modelId: String): String? = null
        override val sampleRate = rate
        override fun generate(text: String, speaker: Int, speed: Float): FloatArray {
            spoken.add(text to speaker)
            return padded(levels.getValue(speaker))
        }
        override fun release() {}
    }

    private val director = object : NarrationDirector {
        override fun enabled(bookId: Int) = true
        override fun direct(bookId: Int, spoken: List<String?>, paragraphEnd: BooleanArray) = emptyList<Direction?>()
        override fun speakerFor(bookId: Int, engine: String, narrator: Int, role: Role) =
            if (role == Role.NARRATOR) narrator else 1
    }

    @Test fun trimmingKeepsThePadsAndFadesTheCuts() {
        val t = trimSilence(padded(0.5f), rate)
        assertEquals(DirectedSpeech.LEAD_PAD_MS, t.lead)
        assertEquals(DirectedSpeech.TAIL_PAD_MS, t.tail)
        assertEquals(15 + 100 + 40, t.pcm.size)
        assertEquals(0f, t.pcm.first(), "faded in")
        assertEquals(0f, t.pcm.last(), "faded out")
        assertEquals(0.5f, abs(t.pcm[15]), "the voice itself is untouched")

        // Audio that starts and ends loud has no pads to give, and no fade into the voice.
        val loud = trimSilence(FloatArray(50) { 0.5f }, rate)
        assertEquals(0, loud.lead)
        assertEquals(50, loud.pcm.size)
        assertEquals(0.5f, loud.pcm.first())

        assertEquals(0, trimSilence(FloatArray(300), rate).pcm.size, "silence trims to nothing")
        // A quiet voice trims as well as a loud one: the threshold follows its peak.
        assertEquals(155, trimSilence(padded(0.01f), rate).pcm.size)
    }

    @Test fun aPauseIsMeasuredVoiceToVoice() {
        val voice = Voice(mapOf(0 to 0.5f, 1 to 0.5f))
        val speech = DirectedSpeech(voice, director, 1)
        val out = speech.speak(
            Direction(listOf(Piece("one", Role.NARRATOR, pauseAfterMs = 300), Piece("two", Role.NARRATOR)), pauseAfterMs = 500),
            ReaderPrefsEngine, narrator = 0, rate = 1f,
        )
        // Piece one: 15 + 100 + 40. Gap to two: 300 less its tail and two's lead-in.
        // Piece two: 15 + 100 + 40. Then 500 less its tail and the next lead-in.
        val expected = 155 + (300 - 40 - 15) + 155 + (500 - 40 - 15)
        assertEquals(expected, out.size)
        assertEquals(300, silentRun(out, from = 15 + 100), "voice one ends, then exactly 300 ms before voice two")

        // At twice the speed a pause is half as long (the pads are not).
        val fast = speech.speak(Direction(listOf(Piece("one", Role.NARRATOR)), pauseAfterMs = 500),
            ReaderPrefsEngine, narrator = 0, rate = 2f)
        assertEquals(155 + (250 - 40 - 15), fast.size)

        // A scene break: nothing to say, the whole pause.
        assertEquals(1000, speech.speak(Direction(emptyList(), 1000), ReaderPrefsEngine, 0, 1f).size)
    }

    @Test fun aQuieterVoiceIsBroughtToTheNarratorsLevel() {
        val voice = Voice(mapOf(0 to 0.4f, 1 to 0.2f))
        val speech = DirectedSpeech(voice, director, 1)
        val narration = speech.speak(Direction(listOf(Piece("n", Role.NARRATOR)), 0), ReaderPrefsEngine, 0, 1f)
        val dialogue = speech.speak(Direction(listOf(Piece("d", Role.DIALOGUE)), 0), ReaderPrefsEngine, 0, 1f)
        assertEquals(0.4f, peak(narration), 1e-4f, "the narrator is left as it is")
        assertEquals(0.4f, peak(dialogue), 1e-4f, "the dialogue voice is raised to match")
    }

    @Test fun gainIsBoundedAndNeverClips() {
        // Ten times quieter: raised by at most 2x.
        val quiet = DirectedSpeech(Voice(mapOf(0 to 0.5f, 1 to 0.05f)), director, 1)
        quiet.speak(Direction(listOf(Piece("n", Role.NARRATOR)), 0), ReaderPrefsEngine, 0, 1f)
        val d = quiet.speak(Direction(listOf(Piece("d", Role.DIALOGUE)), 0), ReaderPrefsEngine, 0, 1f)
        assertEquals(0.1f, peak(d), 1e-4f)

        // A loud narrator against a quiet voice near full scale: held under the peak.
        val loud = DirectedSpeech(Voice(mapOf(0 to 0.9f, 1 to 0.6f)), director, 1)
        loud.speak(Direction(listOf(Piece("n", Role.NARRATOR)), 0), ReaderPrefsEngine, 0, 1f)
        val held = loud.speak(Direction(listOf(Piece("d", Role.DIALOGUE)), 0), ReaderPrefsEngine, 0, 1f)
        assertTrue(peak(held) <= DirectedSpeech.PEAK + 1e-6f, "peak ${peak(held)}")
        assertTrue(peak(held) > 0.6f, "still raised as far as it can go")
    }

    @Test fun aStuttersFragmentGetsItsVoicesGainButTeachesNothing() {
        val voice = Voice(mapOf(0 to 0.4f, 1 to 0.2f))
        val speech = DirectedSpeech(voice, director, 1)
        speech.speak(Direction(listOf(Piece("n", Role.NARRATOR)), 0), ReaderPrefsEngine, 0, 1f)
        speech.speak(Direction(listOf(Piece("d", Role.DIALOGUE)), 0), ReaderPrefsEngine, 0, 1f)
        val fragment = speech.speak(
            Direction(listOf(Piece("Violet", Role.DIALOGUE, pauseAfterMs = 170, onset = 0.5f)), 0),
            ReaderPrefsEngine, 0, 1f,
        )
        assertEquals(0.4f, peak(fragment), 1e-3f, "the fragment is raised like its voice")
    }

    @Test fun aPiecesOwnGainIsAppliedOnTopOfItsVoices() {
        val speech = DirectedSpeech(Voice(mapOf(0 to 0.4f, 1 to 0.4f)), director, 1)
        val whisper = speech.speak(Direction(listOf(Piece("n", Role.NARRATOR, gain = 0.5f)), 0), ReaderPrefsEngine, 0, 1f)
        assertEquals(0.2f, peak(whisper), 1e-4f)
        val shout = speech.speak(Direction(listOf(Piece("n", Role.NARRATOR, gain = 3f)), 0), ReaderPrefsEngine, 0, 1f)
        assertEquals(DirectedSpeech.PEAK, peak(shout), 1e-4f, "a shout never clips")
    }

    @Test fun speechLevelIgnoresThePausesBetweenWords() {
        val words = FloatArray(1000) { if (it < 200 || it in 600 until 800) 0.3f else 0f }
        assertEquals(0.3f, speechRms(words, rate), 1e-4f)
        assertEquals(0f, speechRms(FloatArray(500), rate))
    }

    private fun peak(pcm: FloatArray) = pcm.maxOf { abs(it) }

    /** How many samples from [from] on are silent before sound resumes. */
    private fun silentRun(pcm: FloatArray, from: Int): Int {
        var i = from
        while (i < pcm.size && abs(pcm[i]) < 0.05f) i++
        return i - from
    }

    private companion object { const val ReaderPrefsEngine = "kokoro" }
}
