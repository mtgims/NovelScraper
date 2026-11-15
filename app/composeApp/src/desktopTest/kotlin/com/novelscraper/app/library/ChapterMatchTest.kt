package com.novelscraper.app.library

import com.novelscraper.app.library.ChapterMatch.Ch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterMatchTest {

    private fun list(vararg titles: String, read: Int = 0) =
        titles.mapIndexed { i, t -> Ch(i + 1, "", t, read = i < read) }

    private fun numbered(range: IntRange, read: Int = 0, title: (Int) -> String = { "Chapter $it" }) =
        range.mapIndexed { i, n -> Ch(i + 1, "", title(n), read = i < read) }

    @Test fun numbersAreReadFromTheWaysSitesWriteThem() {
        fun n(title: String, number: String = "") = ChapterMatch.numberOf(number, title)
        assertEquals(12.0, n("Chapter 12"))
        assertEquals(12.5, n("Ch. 12.5 - Interlude"))
        assertEquals(3.0, n("Episode 3"))
        assertEquals(12.0, n("Chapter 12: 3 Days Later"), "the chapter's number, not the one in its name")
        assertEquals(5.0, n("Book 2, Chapter 5"))
        assertEquals(7.0, n("7 - The Storm"))
        assertEquals(40.0, n("第40章 风"))
        assertEquals(9.0, n("Whatever", "9"), "the source's own number comes first")
        assertNull(n("Prologue"))
        assertNull(n("The Storm"))
    }

    @Test fun readMarksAndThePlaceFollowTheNumbersAcrossAPrologue() {
        // The old source starts at chapter 1; the new one has a prologue first,
        // so every chapter sits one place later.
        val old = numbered(1..20, read = 10)
        val new = listOf(Ch(1, "", "Prologue", false)) + numbered(1..25).map { it.copy(position = it.position + 1) }
        val plan = ChapterMatch.plan(old, new, resumePosition = 11)
        assertTrue(plan.byNumber)
        assertEquals((1..11).toSet(), plan.read, "the prologue and chapters 1 to 10")
        assertEquals(12, plan.resume, "chapter 11, one place later")
        assertTrue(plan.exact)
    }

    @Test fun aChapterSplitInPartsIsStillMatchedByNumber() {
        val old = numbered(1..10, read = 5)
        val new = (1..10).flatMap { n -> listOf("Chapter $n Part 1", "Chapter $n Part 2") }
            .mapIndexed { i, t -> Ch(i + 1, "", t, false) }
        val plan = ChapterMatch.plan(old, new, resumePosition = 6)
        assertTrue(plan.byNumber)
        assertEquals((1..10).toSet(), plan.read, "both parts of chapters 1 to 5")
        assertEquals(11, plan.resume, "the first part of chapter 6")
    }

    @Test fun numberingThatStartsAgainEachVolumeIsNotTrusted() {
        val volumes = (1..3).flatMap { v -> (1..10).map { "Volume $v Chapter $it" } }
        val old = list(*volumes.toTypedArray(), read = 15)
        val new = list(*volumes.toTypedArray())
        val plan = ChapterMatch.plan(old, new, resumePosition = 16)
        assertFalse(plan.byNumber, "chapter 5 is three different chapters here")
        assertEquals((1..15).toSet(), plan.read)
        assertEquals(16, plan.resume)
        assertFalse(plan.exact, "a guess, for the reader to check")
    }

    @Test fun titlesWithoutNumbersFallBackToTheOrder() {
        val old = list("The Beginning", "The Middle", "The End", read = 2)
        val new = list("The Beginning", "The Middle", "The End", "Afterword")
        val plan = ChapterMatch.plan(old, new, resumePosition = 3)
        assertFalse(plan.byNumber)
        assertEquals(setOf(1, 2), plan.read)
        assertEquals(3, plan.resume)
    }

    @Test fun aChapterTheOldSourceSkippedBeforeThePlaceCountsAsRead() {
        // The old source never listed chapter 4; the reader is past it.
        val old = (listOf(1, 2, 3, 5, 6, 7)).mapIndexed { i, n -> Ch(i + 1, "", "Chapter $n", read = n < 6) }
        val new = numbered(1..10)
        val plan = ChapterMatch.plan(old, new, resumePosition = 5)   // chapter 6
        assertEquals(setOf(1, 2, 3, 4, 5), plan.read)
        assertEquals(6, plan.resume)
    }

    @Test fun aNewSourceThatEndsBeforeThePlaceStopsAtItsLastChapter() {
        val old = numbered(1..50, read = 40)
        val new = numbered(1..30)
        val plan = ChapterMatch.plan(old, new, resumePosition = 41)
        assertEquals((1..30).toSet(), plan.read)
        assertEquals(30, plan.resume, "as far as it goes")
        assertFalse(plan.exact)
        assertTrue(plan.pastTheEnd)
    }

    @Test fun aPlaceInAnUnnumberedChapterIsFoundByItsTitle() {
        // Reading the interlude between chapters 5 and 6; the new source has a
        // prologue first, so everything sits one place later.
        val titles = (1..5).map { "Chapter $it" } + "Interlude: The Lake" + (6..10).map { "Chapter $it" }
        val old = list(*titles.toTypedArray(), read = 5)
        val new = list("Prologue", *titles.toTypedArray())
        val plan = ChapterMatch.plan(old, new, resumePosition = 6)
        assertTrue(plan.byNumber)
        assertEquals((1..6).toSet(), plan.read, "the prologue and chapters 1 to 5, not the interlude")
        assertEquals(7, plan.resume)
        assertTrue(plan.exact)
    }

    @Test fun aPlaceInAnUnnumberedChapterTheNewSourceLacksGoesToTheChapterAfter() {
        val old = list(*((1..5).map { "Chapter $it" } + "Side Story" + (6..10).map { "Chapter $it" }).toTypedArray(), read = 5)
        val new = numbered(1..10)
        val plan = ChapterMatch.plan(old, new, resumePosition = 6)
        assertEquals((1..5).toSet(), plan.read)
        assertEquals(6, plan.resume, "chapter 6, the next one")
        assertFalse(plan.exact)
    }

    @Test fun aPlaceInAnEpilogueAfterTheLastNumberedChapter() {
        val old = list(*((1..10).map { "Chapter $it" } + "Epilogue").toTypedArray(), read = 10)
        val new = list(*((1..10).map { "Chapter $it" } + "Epilogue").toTypedArray())
        val plan = ChapterMatch.plan(old, new, resumePosition = 11)
        assertEquals((1..10).toSet(), plan.read)
        assertEquals(11, plan.resume)
        assertTrue(plan.exact)
    }

    @Test fun nothingReadMovesNothing() {
        val plan = ChapterMatch.plan(numbered(1..5), numbered(1..5), resumePosition = null)
        assertTrue(plan.read.isEmpty())
        assertNull(plan.resume)
    }
}
