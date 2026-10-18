package com.novelscraper.app.tts

import com.novelscraper.app.tts.NarrationText.Term
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What narration says: the asides it passes over, and how words are said. */
class NarrationTextTest {

    @Test fun passesOverTheSitesOwnAsides() {
        val junk = listOf(
            "Royal Road is the home of this novel. Visit there to read the original and support the author.",
            "Read the latest chapters at novelfire.net.",
            "Support me on Patreon for advance chapters!",
            "Translator: Someone. Editor: Someone Else.",
            "Join our discord server: https://discord.gg/abcdef",
            "If you find any errors (broken links, non-standard content), please let us know.",
            "Please bookmark this site to keep reading.",
        )
        junk.forEach { assertTrue(NarrationText.isJunk(it), "should be passed over: $it") }
    }

    @Test fun leavesTheStoryAlone() {
        val story = listOf(
            "Zorian's eyes abruptly shot open as sharp pain erupted from his stomach.",
            "\"Support me,\" she said, and he laughed at the idea of a knight needing help.",
            "The author of the grimoire had written a note in the margin.",
            "He visited the home of his old translator friend in the city.",
        )
        story.forEach { assertFalse(NarrationText.isJunk(it), "should be spoken: $it") }
    }

    @Test fun readersOwnLinesArePassedOver() {
        assertTrue(NarrationText.isJunk("Chapter sponsored by Acme", listOf("sponsored by")))
        assertFalse(NarrationText.isJunk("He sponsored the expedition", listOf("sponsored by")))
    }

    @Test fun theDictionarySaysWordsDifferently() {
        val terms = listOf(
            Term("Xianxia", "shyen shya"),
            Term("MC", "main character"),
            Term("qi", "chee"),
        )
        assertEquals("A shyen shya tale.", NarrationText.say("A Xianxia tale.", terms))
        assertEquals("main character, again", NarrationText.say("MC, again", terms))
        // Whole words only: "qigong" and "Qingdao" keep their spelling.
        assertEquals("chee flowed, qigong did not", NarrationText.say("qi flowed, qigong did not", terms))
        assertEquals("he said (chee)", NarrationText.say("he said (qi)", terms))
        assertEquals(
            "part of a word stays",
            NarrationText.say("part of a word stays", listOf(Term("art", "ART"))),
        )
        assertEquals(
            "pART of a word",
            NarrationText.say("part of a word", listOf(Term("art", "ART", wholeWord = false))),
        )
    }

    @Test fun aNovelsOwnWordsComeFirst() {
        com.novelscraper.app.data.ReaderPrefs.init()
        val prefs = com.novelscraper.app.data.ReaderPrefs
        prefs.setTtsSkipJunk(true)
        prefs.setTtsJunkPatterns(emptyList())
        prefs.setTtsDictionary(listOf(Term("Baru", "Bah-roo"), Term("qi", "chee")))
        try {
            val novel = listOf(Term("baru", "Bar-oo"))
            assertEquals(listOf("Bar-oo felt chee."), NarrationText.spokenLines(listOf("Baru felt qi."), novel),
                "the novel's way for its name, the general list for the rest")
            assertEquals(listOf("Bah-roo felt chee."), NarrationText.spokenLines(listOf("Baru felt qi.")),
                "without the novel's words, the general list alone")
        } finally {
            prefs.setTtsDictionary(emptyList())
        }
    }
}
