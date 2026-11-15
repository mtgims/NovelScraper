package com.novelscraper.app.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TitleMatchTest {
    private fun of(a: String, b: String) = TitleMatch.of(a, b)

    @Test fun theSameTitleWrittenDifferentlyIsExact() {
        assertEquals(TitleMatch.Kind.Exact, of("Shadow Slave", "shadow slave"))
        assertEquals(TitleMatch.Kind.Exact, of("Reverend Insanity", "Reverend  Insanity "))
        assertEquals(TitleMatch.Kind.Exact, of("Lord of the Mysteries", "Lord Of The Mysteries"))
        assertEquals(TitleMatch.Kind.Exact, of("Sword, Tiara and High Heels", "Sword Tiara & High Heels"))
        assertEquals(TitleMatch.Kind.Exact, of("Pokémon: A New Life", "Pokemon - A New Life"))
        assertEquals(TitleMatch.Kind.Exact, of("The Beginning After the End", "Beginning After the End"))
    }

    @Test fun aSequelOrAFanficIsNotTheNovel() {
        assertEquals(TitleMatch.Kind.None, of("Lord of the Mysteries", "Lord of Mysteries 2: Circle of Inevitability"))
        assertEquals(TitleMatch.Kind.None, of("Shadow Slave", "The Noble Queen-A Shadow Slave Fanfic"))
        assertTrue(of("Martial World", "Martial World (Fanfic)") != TitleMatch.Kind.Exact,
            "a bracketed word is not dropped: it may be what makes it another novel")
    }

    @Test fun aNearTitleIsOfferedButNotTaken() {
        assertEquals(TitleMatch.Kind.Near, of("Lord of the Mysteries", "Lord of Mysteries"))
        assertEquals(TitleMatch.Kind.Near, of("Release That Witch", "Release the Witch"))
        assertEquals(TitleMatch.Kind.Exact, of("Release That Witch", "Release that Witch!!"), "punctuation is not a difference")
    }

    @Test fun aTitleTheSiteCutShortIsNear() {
        // Novel Hall's lists end long titles with "...".
        assertEquals(TitleMatch.Kind.Near, of("Your Spell Version is Too Low", "Your Spell Version is Too Lo..."))
        assertEquals(TitleMatch.Kind.None, of("Your Spell Version is Too Low", "Your Wife Is Too..."),
            "only when what is left is the start of the title")
        assertEquals(TitleMatch.Kind.None, of("Shadow Slave", "Sh..."), "too little left to say")
    }

    @Test fun differentNovelsAreNone() {
        assertEquals(TitleMatch.Kind.None, of("Martial World", "Martial Peak"))
        assertEquals(TitleMatch.Kind.None, of("Shadow Slave", "Shadow Slave 2"))
        assertFalse(of("", "") == TitleMatch.Kind.Exact)
    }
}
