package com.novelscraper.app.tts

import com.novelscraper.app.data.ChapterRead
import com.novelscraper.app.data.ReaderPrefs
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The narrator's playback logic with a fake voice (each sentence's audio carries
 *  its id) and sinks the test controls. */
class NeuralNarratorTest {

    // Three chapters; sentence ids are "chapter*10 + index".
    private fun chapter(pos: Int) = ChapterRead(
        position = pos, number = "$pos", title = "Chapter $pos",
        content = "<p>S${pos}0 one. S${pos}1 two.</p><p>S${pos}2 three.</p>",
        has_prev = pos > 1, has_next = pos < 3,
    )

    private val voice = object : NarratorVoice {
        var problem: String? = null
        override fun prepare(modelId: String) = problem
        override val sampleRate = 1000
        override fun generate(text: String, speaker: Int, speed: Float): FloatArray {
            calls.add(Triple(text, speaker, speed))
            val id = Regex("S(\\d+)").find(text)!!.groupValues[1].toFloat()
            return FloatArray(100) { id / 100f }
        }
        override fun release() {}
    }

    /** Plays instantly unless [hold] is set; records the sentence ids written. */
    private inner class Sink : PcmSink {
        val ids = CopyOnWriteArrayList<Int>()
        @Volatile var closed = false
        @Volatile var hold = false
        private var written = 0L
        override fun write(pcm: FloatArray) {
            ids.add(Math.round(pcm[0] * 100))
            written += pcm.size
            while (hold && !closed) Thread.sleep(5)
        }
        override val framesPlayed: Long get() = if (hold) 0 else written
        override fun close() { closed = true }
    }

    private val calls = CopyOnWriteArrayList<Triple<String, Int, Float>>()

    /** Directs every sentence: the word "said" onwards is narration, the rest
     *  dialogue; a 500 ms pause after each. */
    private val director = object : NarrationDirector {
        @Volatile var on = true
        override fun enabled(bookId: Int) = on
        var ends: BooleanArray? = null
        override fun direct(bookId: Int, spoken: List<String?>, paragraphEnd: BooleanArray): List<Direction?> {
            ends = paragraphEnd
            return spoken.map { t ->
                t?.let {
                    val cut = it.indexOf(" said")
                    val pieces = if (cut < 0) listOf(Piece(it, dialogue = true, speedFactor = 2f))
                    else listOf(Piece(it.substring(0, cut), true), Piece(it.substring(cut + 1), false))
                    Direction(pieces, pauseAfterMs = 500)
                }
            }
        }
        override fun dialogueSpeaker(bookId: Int, engine: String, speaker: Int) = speaker + 7
    }

    private val sinks = CopyOnWriteArrayList<Sink>()
    private val marked = CopyOnWriteArrayList<Int>()
    private var holdNext = false
    private val narrator = NeuralNarrator(
        openSink = { Sink().also { it.hold = holdNext; sinks.add(it) } },
        voice = voice,
        fetchChapter = { _, p -> chapter(p) },
        markRead = { _, p -> marked.add(p) },
    )

    private fun directedNarrator(sink: PcmSink) = NeuralNarrator(
        openSink = { sink },
        voice = voice,
        fetchChapter = { _, p -> chapter(p) },
        markRead = { _, p -> marked.add(p) },
        director = director,
    )

    private fun eventually(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @BeforeTest fun prefs() {
        ReaderPrefs.init()
        ReaderPrefs.setTtsEngine(ReaderPrefs.ENGINE_PIPER)
        ReaderPrefs.setTtsRate(1f)
        ReaderPrefs.setTtsAutoNext(true)
        ReaderPrefs.setTtsSkipJunk(true)
        ReaderPrefs.setTtsJunkPatterns(emptyList())
        ReaderPrefs.setTtsDictionary(emptyList())
        SleepTimer.cancel()
    }

    @AfterTest fun stop() {
        narrator.stop()
        SleepTimer.cancel()
    }

    @Test fun playsInOrderAndRollsIntoTheNextChaptersWithoutAGap() {
        narrator.play(bookId = 5, position = 1, bookTitle = "B", startIndex = 0)
        eventually("the book to finish") { marked.size == 3 && !TtsController.state.value.active }
        assertEquals(listOf(1, 2, 3), marked.toList(), "each chapter counts as read as it is heard")
        assertEquals(1, sinks.size, "one open output for the whole novel: no gap at a chapter")
        assertEquals(listOf(10, 11, 12, 20, 21, 22, 30, 31, 32), sinks.flatMap { it.ids })
    }

    @Test fun passesOverSentencesTheReaderDoesNotWantSpoken() {
        ReaderPrefs.setTtsJunkPatterns(listOf("S11"))   // the second sentence of chapter 1
        ReaderPrefs.setTtsAutoNext(false)
        narrator.play(5, 1, "B", 0)
        eventually("the chapter to finish") { sinks.isNotEmpty() && !TtsController.state.value.active }
        assertEquals(listOf(10, 12), sinks.flatMap { it.ids })
    }

    @Test fun theSleepTimerStopsAtTheChapterEnd() {
        SleepTimer.setChapterEnd()
        narrator.play(5, 1, "B", 0)
        eventually("the chapter to finish") { sinks.isNotEmpty() && !TtsController.state.value.active }
        assertEquals(listOf(10, 11, 12), sinks.flatMap { it.ids }, "it didn't roll into chapter 2")
        assertEquals(SleepTimer.Mode.Off, SleepTimer.mode.value, "the timer is spent")
    }

    @Test fun stopsAtTheChapterEndWithoutAutoNext() {
        ReaderPrefs.setTtsAutoNext(false)
        narrator.play(5, 2, "B", 1)
        eventually("the chapter to finish") { sinks.isNotEmpty() && !TtsController.state.value.active }
        assertEquals(listOf(21, 22), sinks.flatMap { it.ids })
        assertEquals(1, sinks.size)
        assertEquals(listOf(2), marked.toList())
    }

    @Test fun seekRestartsFromThatSentenceAndDropsTheOldAudio() {
        holdNext = true
        narrator.play(5, 1, "B", 0)
        eventually("playback") { sinks.size == 1 && sinks[0].ids.isNotEmpty() }
        val state = TtsController.state.value
        assertTrue(state.active && state.playing && state.bookId == 5 && state.position == 1)
        assertEquals(3, state.sentenceCount)

        narrator.seek(2)
        eventually("the new playback") { sinks.size == 2 && sinks[1].ids.isNotEmpty() }
        assertTrue(sinks[0].closed)
        assertEquals(12, sinks[1].ids.first())
        narrator.stop()
        eventually("stop") { !TtsController.state.value.active }
        assertTrue(sinks[1].closed)
    }

    @Test fun pauseAndResume() {
        holdNext = true
        narrator.play(5, 3, "B", 1)
        eventually("playback") { sinks.size == 1 && sinks[0].ids.isNotEmpty() }
        narrator.toggle()
        eventually("pause") { !TtsController.state.value.playing }
        assertTrue(TtsController.state.value.active, "paused, not stopped")
        assertTrue(sinks[0].closed)
        holdNext = false
        narrator.toggle()
        eventually("the chapter to finish") { !TtsController.state.value.active }
        assertEquals(31, sinks[1].ids.first(), "resumes at the sentence it paused on")
    }

    @Test fun missingVoiceStopsWithoutOpeningAudio() {
        voice.problem = "Download a voice first"
        narrator.play(5, 1, "B", 0)
        eventually("narration to give up") { !TtsController.state.value.active }
        Thread.sleep(200)
        assertTrue(sinks.isEmpty(), "no audio output was opened")
        assertTrue(marked.isEmpty(), "nothing was heard, so nothing was marked read")
    }

    @Test fun aDirectedSentenceIsStillOneWriteWithItsPieceVoicesAndPause() {
        ReaderPrefs.setTtsAutoNext(false)
        val sink = SizedSink()
        val directed = directedNarrator(sink)
        try {
            directed.play(5, 1, "B", 0)
            eventually("the chapter to finish") { sink.sizes.size == 3 && !TtsController.state.value.active }
        } finally { directed.stop() }
        // Chapter 1 is "S10 one. S11 two." then "S12 three." in its own paragraph.
        assertEquals(listOf(false, true, true), director.ends!!.toList())
        // 100 frames of voice + 500 ms of silence at 1000 Hz, one write per sentence.
        assertEquals(listOf(600, 600, 600), sink.sizes.toList())
        val speaker = ReaderPrefs.speakerFor(ReaderPrefs.ENGINE_PIPER)
        assertTrue(calls.all { it.second == speaker + 7 }, "dialogue in the dialogue voice")
        assertTrue(calls.all { it.third == 2f }, "the piece's speed on top of the reader's")
    }

    @Test fun switchedOffItReadsAsBefore() {
        director.on = false
        ReaderPrefs.setTtsAutoNext(false)
        val sink = SizedSink()
        val directed = directedNarrator(sink)
        try {
            directed.play(5, 1, "B", 0)
            eventually("the chapter to finish") { sink.sizes.size == 3 && !TtsController.state.value.active }
        } finally { directed.stop() }
        assertNull(director.ends, "never asked")
        assertEquals(listOf(100, 100, 100), sink.sizes.toList())
        assertTrue(calls.all { it.third == 1f })
    }

    /** Records how long each write was. */
    private class SizedSink : PcmSink {
        val sizes = CopyOnWriteArrayList<Int>()
        private var written = 0L
        override fun write(pcm: FloatArray) { sizes.add(pcm.size); written += pcm.size }
        override val framesPlayed: Long get() = written
        override fun close() {}
    }

    @Test fun theStartOfAWordIsCutFromItsVoicedPart() {
        // 100 ms of silence, 1 s of voice, 100 ms of silence, at 1000 Hz.
        val pcm = FloatArray(1200) { if (it in 100 until 1100) 0.5f else 0f }
        val cut = onsetOf(pcm, 0.17f, 1000)
        assertEquals(170, cut.size, "17% of the voice, the silence before it skipped")
        assertEquals(0.5f, cut[0])
        assertEquals(0f, cut.last(), "faded out, so the cut doesn't click")
        assertEquals(70, onsetOf(pcm, 0.01f, 1000).size, "never shorter than 70 ms")
        assertEquals(250, onsetOf(pcm, 0.9f, 1000).size, "never longer than 250 ms")
        assertEquals(0, onsetOf(FloatArray(500), 0.5f, 1000).size, "silence has no start")
    }
}
