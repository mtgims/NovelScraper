package com.novelscraper.app.ui.browse

import com.novelscraper.app.extensions.NovelItem
import com.novelscraper.app.ui.browse.SourceResults.State
import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalSearchOrderTest {
    private fun row(id: String, state: State) = SourceResults(id, id, state = state)
    private val found = State.Found(listOf(NovelItem("A Novel", "a")))

    @Test fun findsComeFirstAndKeepTheirPlaceAsOthersAnswer() {
        var rows = listOf(
            row("a", State.Searching), row("b", State.Searching),
            row("c", State.Searching), row("d", State.Searching),
        )
        fun answer(id: String, s: State) { rows = orderResults(rows.map { if (it.pluginId == id) it.copy(state = s) else it }) }
        answer("c", found)
        answer("a", State.Failed("down"))
        answer("d", found)
        answer("b", State.Found(emptyList()))
        assertEquals(listOf("c", "d", "b", "a"), rows.map { it.pluginId },
            "c found first and stays first; then d; then nothing found; then the failure")
    }
}
