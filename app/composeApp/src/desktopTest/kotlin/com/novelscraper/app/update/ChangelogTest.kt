package com.novelscraper.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The changelog read into releases, and which of them to show after an update. */
class ChangelogTest {
    private val sample = """
        # NovelScraper, changelog

        Newest first.

        ## 1.3.0, 2026-11-20 · `versionCode 103`

        Sources move in bulk. A second
        line of the same paragraph.

        ### Browse
        - **Migrate** moves a source's novels,
          with a line that runs on.
        - Plain item with `code` and *italics*.

        Notes after the items.

        ## 1.2.0, 2026-10-22 · `versionCode 102`

        - An item before any section.
    """.trimIndent()

    @Test fun releasesSectionsAndItemsAreRead() {
        val r = Changelog.parse(sample)
        assertEquals(listOf("1.3.0", "1.2.0"), r.map { it.version })
        assertEquals("2026-11-20", r[0].date)
        assertEquals(listOf("Sources move in bulk. A second line of the same paragraph."), r[0].intro)
        val browse = r[0].sections.single()
        assertEquals("Browse", browse.title)
        assertEquals(
            listOf(
                Changelog.Block.Item("**Migrate** moves a source's novels, with a line that runs on."),
                Changelog.Block.Item("Plain item with `code` and *italics*."),
                Changelog.Block.Paragraph("Notes after the items."),
            ),
            browse.blocks,
        )
        assertEquals(null, r[1].sections.single().title)
    }

    @Test fun marksBecomeStyles() {
        assertEquals(
            listOf(
                Changelog.Span("Migrate", bold = true), Changelog.Span(" with "), Changelog.Span("code", code = true),
                Changelog.Span(" and "), Changelog.Span("this", italic = true), Changelog.Span(", 5 * 3."),
            ),
            Changelog.spans("**Migrate** with `code` and *this*, 5 * 3."),
        )
    }

    @Test fun theShippedChangelogReadsWhole() {
        val all = Changelog.releases
        assertTrue(all.size > 10, "the app carries its changelog")
        for (r in all) {
            assertTrue(Regex("""\d+\.\d+\.\d+""").matches(r.version), "version of ${r.version}")
            assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(r.date), "date of ${r.version}: ${r.date}")
            assertTrue(r.intro.isNotEmpty() || r.sections.isNotEmpty(), "${r.version} has something to say")
            val texts = r.intro + r.sections.flatMap { s -> s.blocks.map { it.text } }
            for (t in texts) {
                val shown = Changelog.spans(t).joinToString("") { it.text }
                assertTrue("**" !in shown && "`" !in shown, "marks left in ${r.version}: $shown")
            }
        }
    }

    private val releases = Changelog.parse(sample)

    @Test fun aFirstStartShowsNothing() =
        assertEquals(emptyList(), Changelog.toShow("1.3.0", seen = null, hasLibrary = false, all = releases))

    @Test fun anUpdateFromBeforeThisWasWrittenDownShowsTheCurrentRelease() =
        assertEquals(listOf("1.3.0"), Changelog.toShow("1.3.0", seen = null, hasLibrary = true, all = releases).map { it.version })

    @Test fun anUpdateShowsWhatCameSince() {
        assertEquals(listOf("1.3.0"), Changelog.toShow("1.3.0", "1.2.0", false, releases).map { it.version })
        assertEquals(listOf("1.3.0", "1.2.0"), Changelog.toShow("1.3.0", "1.1.0", false, releases).map { it.version })
        assertEquals(emptyList(), Changelog.toShow("1.3.0", "1.3.0", true, releases), "already seen")
    }

    @Test fun aVersionWithoutAnEntryShowsNothingOfItsOwn() =
        assertEquals(emptyList(), Changelog.toShow("1.4.0", seen = null, hasLibrary = true, all = releases))
}
