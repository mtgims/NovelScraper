package com.novelscraper.app.ui.browse

import com.novelscraper.app.extensions.NovelItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A source's first page, kept a few minutes so going back to it is instant. */
class RecentListsTest {
    private var now = 0L
    private val lists = RecentLists(keepMs = 5 * 60_000, max = 3, clock = { now })
    private fun page(vararg names: String) = RecentLists.Page(names.map { NovelItem(it, "/$it") }, "Royal Road", emptyMap())

    @Test fun aListComesBackUntilItIsOld() {
        lists.put("rr", SourceMode.Popular, "", page("A", "B"))
        now += 4 * 60_000
        assertEquals(listOf("A", "B"), lists.get("rr", SourceMode.Popular, "")?.items?.map { it.name })
        now += 2 * 60_000
        assertNull(lists.get("rr", SourceMode.Popular, ""), "kept five minutes, no longer")
    }

    @Test fun eachListAndSearchIsItsOwn() {
        lists.put("rr", SourceMode.Popular, "", page("A"))
        lists.put("rr", SourceMode.Search, "sword", page("S"))
        assertNull(lists.get("rr", SourceMode.Latest, ""))
        assertNull(lists.get("rr", SourceMode.Search, "shield"))
        assertNull(lists.get("sh", SourceMode.Popular, ""))
        assertEquals("S", lists.get("rr", SourceMode.Search, " sword ")?.items?.single()?.name, "the same search, spaced or not")
    }

    @Test fun nothingFoundIsNotKept() {
        lists.put("rr", SourceMode.Search, "zzz", page())
        assertNull(lists.get("rr", SourceMode.Search, "zzz"))
    }

    @Test fun onlyTheNewestFewAreKept() {
        listOf("a", "b", "c", "d").forEach { lists.put(it, SourceMode.Popular, "", page(it)) }
        assertNull(lists.get("a", SourceMode.Popular, ""))
        assertEquals("d", lists.get("d", SourceMode.Popular, "")?.items?.single()?.name)
    }

    @Test fun aSourceCanBeForgotten() {
        lists.put("rr", SourceMode.Popular, "", page("A"))
        lists.put("rr", SourceMode.Latest, "", page("B"))
        lists.put("sh", SourceMode.Popular, "", page("C"))
        lists.forget("rr")
        assertNull(lists.get("rr", SourceMode.Popular, ""))
        assertNull(lists.get("rr", SourceMode.Latest, ""))
        assertEquals("C", lists.get("sh", SourceMode.Popular, "")?.items?.single()?.name)
    }
}
