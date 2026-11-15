package com.novelscraper.app.update

import com.novelscraper.app.extensions.compareVersions
import com.novelscraper.app.platform.Log

/**
 * The changelog shipped inside the app (app/CHANGELOG.md, copied in at build
 * time), read into releases the app can show in its own style.
 *
 * The file is written for people reading it on GitHub, so only what it uses is
 * understood: a `## version, date` heading per release, `### ` sections,
 * paragraphs, `- ` items whose lines run on indented, and `**bold**`,
 * `*italic*` and `` `code` `` inside them.
 */
object Changelog {
    data class Release(val version: String, val date: String, val intro: List<String>, val sections: List<Section>)

    data class Section(val title: String?, val blocks: List<Block>)

    sealed interface Block {
        val text: String
        data class Paragraph(override val text: String) : Block
        data class Item(override val text: String) : Block
    }

    /** A run of text with one style, for drawing a paragraph or an item. */
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

    private const val RESOURCE = "/changelog/CHANGELOG.md"

    /** Every release, newest first; none if the file is missing or can't be read. */
    val releases: List<Release> by lazy {
        runCatching {
            Changelog::class.java.getResourceAsStream(RESOURCE)?.use { parse(it.readBytes().decodeToString()) }
        }.onFailure { Log.w("Changelog", "couldn't read the changelog: ${it.message}") }
            .getOrNull().orEmpty()
    }

    fun parse(markdown: String): List<Release> {
        val releases = ArrayList<Release>()
        var version: String? = null
        var date = ""
        val intro = ArrayList<String>()
        val sections = ArrayList<Section>()
        var sectionTitle: String? = null
        var blocks = ArrayList<Block>()
        // The block being read, line by line: a paragraph or an item.
        val current = StringBuilder()
        var currentIsItem = false

        fun endBlock() {
            if (current.isEmpty()) return
            val text = current.toString().trim()
            current.clear()
            if (version == null) return
            when {
                currentIsItem -> blocks += Block.Item(text)
                sectionTitle == null && sections.isEmpty() && blocks.isEmpty() -> intro += text
                else -> blocks += Block.Paragraph(text)
            }
        }
        fun endSection() {
            endBlock()
            if (blocks.isNotEmpty()) sections += Section(sectionTitle, blocks)
            blocks = ArrayList()
        }
        fun endRelease() {
            endSection()
            version?.let { releases += Release(it, date, intro.toList(), sections.toList()) }
            intro.clear(); sections.clear(); sectionTitle = null
        }

        for (raw in markdown.lineSequence()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> {
                    endRelease()
                    val heading = line.removePrefix("## ").substringBefore(" · ").trim()
                    version = heading.substringBefore(",").trim()
                    date = heading.substringAfter(",", "").trim()
                }
                line.startsWith("### ") -> {
                    endSection()
                    sectionTitle = line.removePrefix("### ").trim()
                }
                line.startsWith("# ") -> endBlock()
                line.isBlank() -> endBlock()
                line.startsWith("- ") -> {
                    endBlock()
                    currentIsItem = true
                    current.append(line.removePrefix("- ").trim())
                }
                else -> {
                    // An item's next line, indented, or a paragraph's.
                    if (current.isEmpty()) currentIsItem = false
                    else current.append(' ')
                    current.append(line.trim())
                }
            }
        }
        endRelease()
        return releases
    }

    /**
     * A release's notes as GitHub has them ([body], the changelog entry without
     * its heading), told short for the update card: the summary paragraph, or
     * the first section when there is none, as plain running text. Shown as
     * they came, the lines broke where the file wraps them, mid-sentence in
     * any narrower window, and the marks showed as stars and backticks.
     */
    fun summary(body: String): String {
        val release = parse("## new\n\n$body").firstOrNull() ?: return ""
        val blocks = release.intro.ifEmpty { release.sections.firstOrNull()?.blocks?.map { it.text }.orEmpty() }
        return blocks.joinToString("\n") { text -> spans(text).joinToString("") { it.text } }
    }

    /** [text] cut into styled runs; the marks themselves are dropped. */
    fun spans(text: String): List<Span> {
        val out = ArrayList<Span>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { out += Span(plain.toString()); plain.clear() } }
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            val (mark, make) = when {
                rest.startsWith("**") -> "**" to { s: String -> Span(s, bold = true) }
                rest.startsWith("`") -> "`" to { s: String -> Span(s, code = true) }
                // A lone star opens italics only before a letter: "5 * 3" stays as it is.
                rest.startsWith("*") && rest.length > 1 && !rest[1].isWhitespace() -> "*" to { s: String -> Span(s, italic = true) }
                else -> null to null
            }
            val end = mark?.let { text.indexOf(it, i + it.length) } ?: -1
            if (mark != null && make != null && end > i + mark.length - 1) {
                flush()
                out += make(text.substring(i + mark.length, end))
                i = end + mark.length
            } else {
                plain.append(text[i]); i++
            }
        }
        flush()
        return out
    }

    /**
     * The releases to show once after an update: those newer than [seen], up to
     * [current]. With nothing seen yet, a library that already has novels means
     * the app was there before (an update from before this was written down), so
     * the release running is shown; an empty one means a first start, which needs
     * no news.
     */
    fun toShow(current: String, seen: String?, hasLibrary: Boolean, all: List<Release>): List<Release> = when {
        seen == null -> if (hasLibrary) all.filter { compareVersions(it.version, current) == 0 } else emptyList()
        compareVersions(seen, current) >= 0 -> emptyList()
        else -> all.filter { compareVersions(it.version, seen) > 0 && compareVersions(it.version, current) <= 0 }
    }
}
