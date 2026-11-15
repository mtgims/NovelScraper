package com.novelscraper.app.library

import kotlin.test.Test
import kotlin.test.assertEquals

/** The statistics page's sums. */
class ReadingStatsTest {
    private var id = 0
    private fun book(
        read: Int, total: Int, status: String = "Ongoing", site: String = "Site A", genres: String = "",
        rating: Int? = null, opened: Long = 0, downloaded: Int = 0,
    ) = LibBook(
        id = ++id, title = "Novel $id", author = "", cover = null, site = site, rating = rating, inLibrary = true,
        pluginId = "p", path = "n$id", genres = genres, status = status,
        chapterCount = total, unreadCount = total - read, downloadedCount = downloaded, openedAt = opened,
    )

    @Test fun everyNovelHasOneState() {
        val s = ReadingStats.of(listOf(
            book(10, 10, status = "Completed"),
            book(10, 10, status = "Ongoing"),
            book(3, 10),
            book(0, 10),
            book(0, 0),
        ))
        assertEquals(listOf(1, 1, 1, 2), listOf(s.finished, s.caughtUp, s.reading, s.notStarted),
            "complete and all read is finished; all read of one still coming out is caught up")
        assertEquals(23, s.chaptersRead)
        assertEquals(40, s.chapters)
        assertEquals(3, s.started)
    }

    @Test fun sourcesAndGenresAreRankedAndRatingsCounted() {
        val s = ReadingStats.of(listOf(
            book(5, 9, site = "Site A", genres = "Action, Fantasy", rating = 5),
            book(7, 9, site = "Site B", genres = "action; Romance", rating = 5),
            book(1, 9, site = "Site A", genres = "Fantasy, Fantasy", rating = 2),
            book(0, 9, site = "Site C"),
        ))
        assertEquals(listOf("Site B" to 7, "Site A" to 6), s.bySource, "only sources something was read from")
        assertEquals(listOf("Action" to 2, "Fantasy" to 2, "Romance" to 1), s.byGenre, "case folded, counted once per novel")
        assertEquals(listOf(0, 1, 0, 0, 2), s.ratings)
        assertEquals(4f, s.averageRating)
    }

    @Test fun continueReadingIsTheStartedOnesOpenedLastFirst() {
        val s = ReadingStats.of(listOf(book(2, 9, opened = 100), book(9, 9, opened = 500), book(4, 9, opened = 300), book(0, 9, opened = 900)))
        assertEquals(listOf(300L, 100L), s.continueReading.map { it.book.openedAt })
    }

    @Test fun wordsAreEstimatedFromTheChaptersStillHere() {
        val s = ReadingStats.of(listOf(book(100, 200)))
        assertEquals(200_000L, s.wordsRead(WordSample(words = 20_000, chapters = 10)), "2,000 a chapter, 100 read")
        assertEquals(800L, s.minutesRead(WordSample(words = 20_000, chapters = 10)))
        assertEquals(0L, s.wordsRead(WordSample.NONE))
    }
}
