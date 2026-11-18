package com.novelscraper.app.ui.layout

import com.novelscraper.app.library.LibBook
import com.novelscraper.app.library.ReadingStats
import com.novelscraper.app.library.WordSample
import com.novelscraper.app.ui.screen.StatsContent
import kotlin.test.Test

/** The statistics page at phone sizes, with a library like a real one. */
class StatsLayoutTest {
    private var id = 0
    private fun book(read: Int, total: Int, title: String, site: String, genres: String, rating: Int?, status: String = "Ongoing") = LibBook(
        id = ++id, title = title, author = "", cover = null, site = site, rating = rating, inLibrary = true,
        pluginId = "p", path = "n$id", genres = genres, status = status,
        chapterCount = total, unreadCount = total - read, downloadedCount = 0, openedAt = id.toLong(),
    )

    private val library = listOf(
        book(26, 2140, "Infinite Mana in the Apocalypse", "Novel Fire", "Action,Adventure,Fantasy,LitRPG", 5),
        book(1450, 2950, "Shadow Slave", "Novel Phoenix", "Action,Fantasy,Mystery", 5),
        book(11, 1432, "Lord of the Mysteries", "Royal Road", "Mystery,Supernatural,Steampunk", null),
        book(640, 640, "A Very Long Title That Goes On For Quite A While: The Second Volume Of It", "Scribble Hub",
            "Romance,Slice of Life", null, status = "Completed"),
        book(0, 312, "Reverend Insanity", "Wuxiabox", "Xianxia,Dark", null),
    )

    /** Five stars only, as the reader had it when the chart spilled out. */
    @Test
    fun theStatisticsFitWithOnlyFiveStarRatings() {
        val s = ReadingStats.of(library)
        LayoutCheck.assertFits("The statistics page", height = 3200) { StatsContent(s, WordSample(1_234_567, 400)) {} }
    }

    @Test
    fun theStatisticsFitWithRatingsSpreadOut() {
        val s = ReadingStats.of(library.mapIndexed { i, b -> b.copy(rating = i + 1) })
        LayoutCheck.assertFits("The statistics page", height = 3200) { StatsContent(s, null) {} }
    }
}
