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
        ReaderPrefs.setTtsExpressive(false)
        NovelVoicings.set(BOOK, NovelVoicing())
        ReaderPrefs.setKokoroDialogueSpeaker(-1)
        ReaderPrefs.setSupertonicDialogueSpeaker(-1)
    }

    /** Each piece as "D:text" or "N:text". */
    private fun shape(text: String, quoted: Boolean = false) =
        ExpressiveReading.split(text, ExpressiveReading.QuoteState(if (quoted) '"' else null)).first.map { (if (it.dialogue) "D:" else "N:") + it.text }

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
        assertEquals(ExpressiveReading.PAUSE_SENTENCE, ExpressiveReading.pauseFor("He sat down."))
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
        assertEquals(3, ExpressiveReading.dialogueSpeaker(BOOK, ReaderPrefs.ENGINE_PIPER, 3))
    }

    @Test fun theReadersChoiceWins() {
        ReaderPrefs.setKokoroDialogueSpeaker(KokoroVoices.all.first { it.code == "bm_fable" }.id)
        assertEquals("bm_fable", KokoroVoices.voice(ExpressiveReading.dialogueSpeaker(BOOK, ReaderPrefs.ENGINE_KOKORO, 0))!!.code)
        ReaderPrefs.setSupertonicDialogueSpeaker(2)
        assertEquals(2, ExpressiveReading.dialogueSpeaker(BOOK, ReaderPrefs.ENGINE_SUPERTONIC, 0))
    }

    /** Each beat as "text|pause" or "~text~share|pause" for a cut-off start. */
    private fun beats(text: String) = ExpressiveReading.beats(text, false, 1f).map {
        (if (it.onset > 0f) "~${it.text}~${"%.2f".format(it.onset)}" else it.text) + "|" + it.pauseAfterMs
    }

    @Test fun aStutterIsTheStartOfTheWordItself() {
        assertEquals(listOf("~Violet~0.17|170", "Violet.|0"), beats("V-Violet."))
        assertEquals(listOf("~You're~0.50|170", "You're alive.|0"), beats("You-You're alive."))
        assertEquals(listOf("~Violet~0.17|170", "~Violet~0.17|170", "Violet!|0"), beats("V-V-Violet!"))
    }

    @Test fun aStutterSetWithADashIsAStutterToo() {
        // As a novel in the library has it: an en dash, and ellipses.
        assertEquals(listOf("~help~0.25|170", "help…|500", "me…|0"), beats("H–help… me…"))
        assertEquals(listOf("~Help~0.25|170", "Help me.|0"), beats("H‑Help me."))
        assertEquals(listOf("I|130", "I don't.|0"), beats("I—I don't."))
    }

    @Test fun aRepeatedWordIsSaidTwiceWithABeat() {
        assertEquals(listOf("Violet, that|130", "that hurts.|0"), beats("Violet, that-that hurts."))
        assertEquals(listOf("I|130", "I|130", "I don't know.|0"), beats("I-I-I don't know."))
    }

    @Test fun anEllipsisOrDashWithinASentenceIsAPause() {
        assertEquals(
            listOf("You need...|500", "you need to lift the beams, please,|0"),
            beats("You need... you need to lift the beams, please,"),
        )
        assertEquals(listOf("I wanted|300", "never mind.|0"), beats("I wanted—never mind."))
        assertEquals(listOf("Wait…|500", "what?|0"), beats("Wait… what?"))
        // At the very end it is the sentence's pause, not a beat.
        assertEquals(listOf("And then...|0"), beats("And then..."))
    }

    @Test fun hyphenatedWordsAreNotStutters() {
        assertEquals(listOf("A well-known, re-read, co-op book.|0"), beats("A well-known, re-read, co-op book."))
    }

    @Test fun yourLinesFromStartToEnd() {
        val spoken = listOf("\"V-Violet.", "You-You're alive.\"",
            "\"Violet, that-that hurts. You need... you need to lift the beams, please,\" he said.")
        val d = ExpressiveReading.direct(spoken, booleanArrayOf(false, true, true))
        assertEquals(listOf("~Violet~0.17", "Violet."), d[0]!!.pieces.map { p ->
            if (p.onset > 0f) "~${p.text}~${"%.2f".format(p.onset)}" else p.text })
        assertEquals(0, d[0]!!.pieces.last().pauseAfterMs, "the sentence's own pause is not doubled")
        assertEquals(
            listOf("\"Violet, that", "that hurts. You need...", "you need to lift the beams, please,\"", "he said."),
            d[2]!!.pieces.map { it.text },
        )
        assertEquals(listOf(true, true, true, false), d[2]!!.pieces.map { it.dialogue })
    }

    /** Each piece as "D:text" or "N:text", with the novel's [quotes]. */
    private fun marked(text: String, vararg quotes: Quote, paragraphStart: Boolean = true) =
        ExpressiveReading.split(text, quotes = quotes.toSet(), paragraphStart = paragraphStart).first
            .map { (if (it.dialogue) "D:" else "N:") + it.text }

    @Test fun singleQuotesWhenTheNovelSpeaksInThem() {
        assertEquals(listOf("D:‘Don’t go,’", "N:she said."), marked("‘Don’t go,’ she said.", Quote.SINGLE_CURLY))
        assertEquals(listOf("D:'I can't,'", "N:he said."), marked("'I can't,' he said.", Quote.SINGLE_STRAIGHT))
        // Not picked: the same line is all narration.
        assertEquals(listOf("N:‘Don’t go,’ she said."), marked("‘Don’t go,’ she said.", *Quote.AUTOMATIC.toTypedArray()))
        // Nor do double quotes count once the novel is set to single ones.
        assertEquals(listOf("N:He said \"no\"."), marked("He said \"no\".", Quote.SINGLE_CURLY))
    }

    @Test fun guillemetsAndDashLedDialogue() {
        assertEquals(listOf("D:«Bonjour»", "N:, dit-il."), marked("«Bonjour», dit-il.", Quote.GUILLEMETS))
        // A paragraph opened by a dash speaks; each later dash turns narration on and off.
        assertEquals(
            listOf("D:—Hola", "N:—dijo él", "D:—. ¿Cómo estás?"),
            marked("—Hola —dijo él—. ¿Cómo estás?", Quote.DASH),
        )
        // A dash in the middle of an ordinary paragraph is only a dash.
        assertEquals(listOf("N:He waited—nothing."), marked("He waited—nothing.", Quote.DASH, paragraphStart = true))
    }

    @Test fun onlyTheNovelsStutterMarksAreStutters() {
        val hyphenOnly = setOf(StutterMark.HYPHEN)
        assertEquals(listOf("~Violet~0.17|170", "Violet.|0"),
            ExpressiveReading.beats("V-Violet.", false, 1f, hyphenOnly).map(::show))
        assertEquals(listOf("V|300", "Violet.|0"),
            ExpressiveReading.beats("V–Violet.", false, 1f, hyphenOnly).map(::show), "an en dash is only a dash here")
        assertEquals(listOf("V-Violet.|0"), ExpressiveReading.beats("V-Violet.", false, 1f, emptySet()).map(::show))
    }

    private fun show(p: Piece) =
        (if (p.onset > 0f) "~${p.text}~${"%.2f".format(p.onset)}" else p.text) + "|" + p.pauseAfterMs

    @Test fun aNovelsOwnSettingsWinOverTheGeneralOnes() {
        assertEquals(false, ExpressiveReading.enabled(BOOK), "follows the general switch, off")
        ReaderPrefs.setTtsExpressive(true)
        assertEquals(true, ExpressiveReading.enabled(BOOK))

        val fable = KokoroVoices.all.first { it.code == "bm_fable" }.id
        NovelVoicings.set(BOOK, NovelVoicing(enabled = false, kokoroDialogue = fable, quotes = setOf(Quote.SINGLE_CURLY)))
        assertEquals(false, ExpressiveReading.enabled(BOOK), "off for this novel")
        assertEquals(true, ExpressiveReading.enabled(BOOK + 1), "others still follow the switch")
        assertEquals(fable, ExpressiveReading.dialogueSpeaker(BOOK, ReaderPrefs.ENGINE_KOKORO, 0))
        val d = ExpressiveReading.direct(BOOK, listOf("‘Run,’ she said."), booleanArrayOf(true))
        assertEquals(listOf(true, false), d[0]!!.pieces.map { it.dialogue }, "the novel's own quote marks")

        NovelVoicings.set(BOOK, NovelVoicing())
        assertEquals(true, ExpressiveReading.enabled(BOOK), "reset: back to the general switch")
    }

    @Test fun detectionCountsTheMarksANovelUses() {
        val british = List(5) { "<p>‘Wait,’ she said. ‘It’s – it’s fine.’</p><p>‘W–What?’</p>" }
        val found = VoicingDetector.detect(british)
        assertEquals(setOf(Quote.SINGLE_CURLY), found.suggestedQuotes)
        assertEquals(setOf(StutterMark.EN_DASH), found.suggestedStutters)

        val american = List(5) { "<p>&ldquo;Wait,&rdquo; she said. &ldquo;It&rsquo;s fine. He said &lsquo;no&rsquo;.&rdquo;</p>" }
        val us = VoicingDetector.detect(american)
        assertEquals(setOf(Quote.DOUBLE_CURLY), us.suggestedQuotes, "a quote within a quote isn't the novel's dialogue")
        assertNull(us.suggestedStutters, "no stutters seen: every mark stays on")

        val spanish = List(5) { "<p>—Hola —dijo él—. ¿Cómo estás?</p><p>—Bien.</p>" }
        assertEquals(setOf(Quote.DASH), VoicingDetector.detect(spanish).suggestedQuotes)
        assertEquals(0, VoicingDetector.detect(emptyList()).chapters)
    }

    @Test fun theNarrationSaysHowALineWasSpoken() {
        assertEquals(ExpressiveReading.Tone.WHISPER, ExpressiveReading.toneOf("she whispered, leaning close"))
        assertEquals(ExpressiveReading.Tone.WHISPER, ExpressiveReading.toneOf("he said under his breath"))
        assertEquals(ExpressiveReading.Tone.SHOUT, ExpressiveReading.toneOf("Klein shouted"))
        assertEquals(ExpressiveReading.Tone.PLAIN, ExpressiveReading.toneOf("by a roaring fire"), "not a verb of speech")
        assertEquals(ExpressiveReading.Tone.PLAIN, ExpressiveReading.toneOf("he whispered, then yelled"), "both: neither")
    }

    @Test fun aWhisperIsQuieterAndSlowerInItsParagraphOnly() {
        val spoken = listOf("\"Stay here,\" she whispered.", "\"Why?\"", "\"Because.\"")
        val d = ExpressiveReading.direct(spoken, booleanArrayOf(false, true, true))
        val first = d[0]!!.pieces
        assertEquals(ExpressiveReading.WHISPER_GAIN, first[0].gain, "the line whispered")
        assertEquals(ExpressiveReading.WHISPER_SPEED, first[0].speedFactor, 1e-4f)
        assertEquals(1f, first[1].gain, "the narration keeps its level")
        assertEquals(ExpressiveReading.WHISPER_GAIN, d[1]!!.pieces[0].gain, "the same paragraph, the same speaker")
        assertEquals(1f, d[2]!!.pieces[0].gain, "the next paragraph is someone else")
    }

    @Test fun aLineInCapitalsIsAShout() {
        val d = ExpressiveReading.direct(listOf("\"STOP RIGHT THERE!\" he said."), booleanArrayOf(true))
        val line = d[0]!!.pieces[0]
        assertEquals(ExpressiveReading.SHOUT_GAIN, line.gain)
        assertEquals("\"STOP RIGHT THERE!\"", line.text, "said as written")

        // An acronym is not a shout, and is left as written.
        val fbi = ExpressiveReading.direct(listOf("\"The FBI is here.\""), booleanArrayOf(true))[0]!!.pieces[0]
        assertEquals(1f, fbi.gain)
        assertEquals("\"The FBI is here.\"", fbi.text)
    }

    @Test fun whispersAndShoutsCanBeTurnedOff() {
        val d = ExpressiveReading.direct(
            listOf("\"STOP!\" she screamed."), booleanArrayOf(true), dynamics = false,
        )
        assertEquals(1f, d[0]!!.pieces[0].gain)
        assertEquals("\"STOP!\"", d[0]!!.pieces[0].text)
    }

    private companion object { const val BOOK = 4242 }
}
