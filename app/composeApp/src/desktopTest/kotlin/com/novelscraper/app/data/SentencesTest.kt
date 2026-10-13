package com.novelscraper.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** Where a sentence ends, which the reader and the narrator both index by. */
class SentencesTest {

    private fun split(text: String) = Sentences.ranges(text).map { text.substring(it) }

    @Test fun ordinaryProse() {
        assertEquals(
            listOf("One thing happened.", "Then another!", "And a third?"),
            split("One thing happened. Then another! And a third?"),
        )
    }

    /** The reason this changed. CJK writes no space after its full stop, and the
     *  old rule required one, so a whole paragraph came back as one sentence:
     *  narration read it as a single unbroken run, and tap-to-start had one
     *  place to land in the whole of it. */
    @Test fun chineseSplitsWithoutSpaces() {
        assertEquals(listOf("你好。", "世界。", "再见！"), split("你好。世界。再见！"))
    }

    @Test fun japaneseSplitsWithoutSpaces() {
        assertEquals(listOf("こんにちは。", "元気ですか？"), split("こんにちは。元気ですか？"))
    }

    /** A line break always ends a sentence, whatever BreakIterator thinks. */
    @Test fun linesAlwaysBreak() {
        assertEquals(listOf("First line", "Second line"), split("First line\nSecond line"))
    }

    /** The contract the reader draws image blocks from and the narrator reads as
     *  silence: a placeholder is a sentence of its own. */
    @Test fun anImagePlaceholderStandsAlone() {
        assertEquals(listOf("Text before", "￼", "Text after"), split("Text before\n￼\nText after"))
    }

    /**
     * Recorded as it is, not as anyone would want it.
     *
     * java.text.BreakIterator knows no abbreviations, so "Mr." ends a sentence.
     * The rule it replaced did the same thing, so nothing regressed, but it is
     * worth a failing test the day something fixes it rather than a surprise.
     * Fixing it properly means ICU, which is a dependency this does not have.
     */
    @Test fun abbreviationsStillSplitWrongly() {
        assertEquals(
            listOf("Mr.", "Smith went home.", "He slept."),
            split("Mr. Smith went home. He slept."),
        )
    }
}
