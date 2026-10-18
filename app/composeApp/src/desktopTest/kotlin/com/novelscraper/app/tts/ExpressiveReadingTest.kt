package com.novelscraper.app.tts

import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.tts.ExpressiveReading.FASTER
import com.novelscraper.app.tts.ExpressiveReading.PAUSE_BROKEN_OFF
import com.novelscraper.app.tts.ExpressiveReading.PAUSE_EXCHANGE
import com.novelscraper.app.tts.ExpressiveReading.PAUSE_PARAGRAPH
import com.novelscraper.app.tts.ExpressiveReading.PAUSE_SCENE
import com.novelscraper.app.tts.ExpressiveReading.PAUSE_TRAILING
import com.novelscraper.app.tts.ExpressiveReading.SLOWER
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Expressive reading: which stretches are dialogue, the pauses, the pacing. */
class ExpressiveReadingTest {

    @BeforeTest fun prefs() {
        ReaderPrefs.init()
        ReaderPrefs.setKokoroDialogueSpeaker(-1)
        ReaderPrefs.setSupertonicDialogueSpeaker(-1)
    }

    /** Each piece as "D:text" or "N:text". */
    private fun shape(text: String, quoted: Boolean = false) =
        ExpressiveReading.split(text, quoted).first.map { (if (it.dialogue) "D:" else "N:") + it.text }

    @Test fun cutsASentenceAtItsQuotes() {
        assertEquals(listOf("D:\"Run,\"", "N:he said,", "D:\"now.\""), shape("\"Run,\" he said, \"now.\""))
        assertEquals(listOf("N:She whispered,", "D:“Stay here.”"), shape("She whispered, “Stay here.”"))
        assertEquals(listOf("D:「待って」", "N:と言った。"), shape("「待って」と言った。"))
        assertEquals(listOf("N:He walked on."), shape("He walked on."))
    }

    @Test fun leavesApostrophesAlone() {
        assertEquals(listOf("N:It's the knight's sword, isn't it?"), shape("It's the knight's sword, isn't it?"))
    }

    @Test fun aStrayScrapJoinsItsNeighbour() {
        // The closing quote and comma would be a piece of their own.
        assertEquals(listOf("D:\"Wait\"", "N:, she said."), shape("\"Wait\", she said."))
        assertEquals(listOf("D:\"No!\" —"), shape("\"No!\" —"))
    }

    @Test fun aQuoteCarriesIntoTheNextSentenceAndEndsWithTheParagraph() {
        val spoken = listOf("\"Hello.", "How are you?\" she asked.", "Silence.", "He left.")
        val ends = booleanArrayOf(false, false, true, true)
        val d = ExpressiveReading.direct(spoken, ends)
        assertEquals(listOf(true), d[0]!!.pieces.map { it.dialogue })
        assertEquals(listOf(true, false), d[1]!!.pieces.map { it.dialogue })
        assertEquals(listOf(false), d[2]!!.pieces.map { it.dialogue })

        // An unclosed quote is forgotten at the paragraph's end.
        val open = ExpressiveReading.direct(listOf("\"I went on and on.", "He sat."), booleanArrayOf(true, true))
        assertEquals(listOf(false), open[1]!!.pieces.map { it.dialogue })
    }

    @Test fun pausesFollowTheText() {
        val spoken = listOf(
            "He paused...", "Then—", "He went on.",
            "\"Who?\"", "\"Me.\"",
            "***",
            null,
            "The end.",
        )
        val ends = booleanArrayOf(false, false, true, true, true, true, true, true)
        val d = ExpressiveReading.direct(spoken, ends)
        assertEquals(PAUSE_TRAILING, d[0]!!.pauseAfterMs)
        assertEquals(PAUSE_BROKEN_OFF, d[1]!!.pauseAfterMs)
        assertEquals(PAUSE_PARAGRAPH, d[2]!!.pauseAfterMs)
        assertEquals(PAUSE_EXCHANGE, d[3]!!.pauseAfterMs, "one line of dialogue answered by another")
        assertEquals(PAUSE_PARAGRAPH, d[4]!!.pauseAfterMs)
        assertEquals(PAUSE_SCENE, d[5]!!.pauseAfterMs)
        assertEquals(emptyList(), d[5]!!.pieces, "a scene break is not spoken")
        assertNull(d[6], "a sentence passed over stays passed over")
        assertEquals(PAUSE_PARAGRAPH, d[7]!!.pauseAfterMs)
        assertEquals(0, ExpressiveReading.pauseFor("He sat down."))
    }

    @Test fun pacingIsSlight() {
        assertEquals(FASTER, ExpressiveReading.speedFor("\"Get down!\""))
        assertEquals(SLOWER, ExpressiveReading.speedFor((1..31).joinToString(" ") { "word" } + "."))
        assertEquals(1f, ExpressiveReading.speedFor("He sat down by the fire."))
    }

    @Test fun dialogueIsReadByAVoiceOfTheOtherKind() {
        val code = { id: Int -> KokoroVoices.voice(ExpressiveReading.kokoroPartner(id))!!.code }
        assertEquals("am_michael", code(KokoroVoices.all.first { it.code == "af_heart" }.id))
        assertEquals("af_heart", code(KokoroVoices.all.first { it.code == "am_onyx" }.id))
        assertEquals("bf_emma", code(KokoroVoices.all.first { it.code == "bm_lewis" }.id))
        // French has one voice: another of the same language does not exist, so it keeps its own.
        val siwis = KokoroVoices.all.first { it.code == "ff_siwis" }.id
        assertEquals(siwis, ExpressiveReading.kokoroPartner(siwis))

        assertEquals(5, ExpressiveReading.supertonicPartner(0))
        assertEquals(1, ExpressiveReading.supertonicPartner(6))
        assertEquals(3, ExpressiveReading.dialogueSpeaker(ReaderPrefs.ENGINE_PIPER, 3))
    }

    @Test fun theReadersChoiceWins() {
        ReaderPrefs.setKokoroDialogueSpeaker(KokoroVoices.all.first { it.code == "bm_fable" }.id)
        assertEquals("bm_fable", KokoroVoices.voice(ExpressiveReading.dialogueSpeaker(ReaderPrefs.ENGINE_KOKORO, 0))!!.code)
        ReaderPrefs.setSupertonicDialogueSpeaker(2)
        assertEquals(2, ExpressiveReading.dialogueSpeaker(ReaderPrefs.ENGINE_SUPERTONIC, 0))
    }
}
