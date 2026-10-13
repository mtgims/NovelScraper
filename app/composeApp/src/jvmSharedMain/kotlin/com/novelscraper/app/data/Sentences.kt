package com.novelscraper.app.data

import com.novelscraper.app.platform.htmlToPlain
import java.text.BreakIterator
import java.util.Locale

/**
 * Shared sentence splitting so the reader (highlight / tap-to-start) and the
 * TtsService (what gets spoken) agree on sentence *indices*. Both derive from
 * the same plain-text form of the chapter HTML and the same split rule.
 */
object Sentences {
    /** The Object Replacement Character HtmlCompat substitutes for each <img>.
     *  It stays in plain() as its own isolated line, so it becomes one "sentence"
     *  the reader renders as an image block and TtsService treats as silence. */
    const val OBJ = '\uFFFC'

    private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SRC_ATTR = Regex("\\bsrc\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

    /** Each <img> tag's src in document order — the SAME order as the OBJ
     *  placeholders HtmlCompat emits in plain() (one per tag, srcless tags
     *  included as ""), so the k-th OBJ maps to the k-th entry here. */
    fun imageSrcs(html: String): List<String> =
        IMG_TAG.findAll(html).map { SRC_ATTR.find(it.value)?.groupValues?.get(1) ?: "" }.toList()

    // An image placeholder plus any whitespace hugging it — collapsed so the image
    // lands on its own line.
    private val OBJ_PAD = Regex("\\s*\uFFFC\\s*")

    fun plain(html: String): String {
        val raw = htmlToPlain(html)
        // Put every image placeholder on its own line. HtmlCompat doesn't reliably
        // break around <img>, and an inline image sharing a text line renders ON TOP
        // of that text; isolating it lets the reader draw it as a block. Shared by
        // the reader and TtsService, so sentence indices stay in lockstep.
        return raw.replace(OBJ_PAD, "\n\uFFFC\n").trim()
    }

    /**
     * Character ranges (inclusive) of each non-empty sentence in [plain].
     *
     * Lines are split first, then each line by [BreakIterator]. The line pass
     * is not an optimisation: an image placeholder sits alone on its own line
     * and has to stay one "sentence" of its own, which is the contract the
     * reader draws image blocks from and the narrator reads as silence.
     *
     * Within a line the work is BreakIterator's. The rule here used to be a
     * regular expression needing whitespace after the terminator, which Chinese
     * and Japanese do not write: a whole CJK paragraph came back as a single
     * sentence, so narration read it as one unbroken run and tap-to-start had
     * one place to land in the lot.
     */
    fun ranges(plain: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var pos = 0
        while (pos <= plain.length) {
            val newline = plain.indexOf('\n', pos)
            val lineEnd = if (newline < 0) plain.length else newline
            if (lineEnd > pos) splitLine(plain, pos, lineEnd, out)
            if (newline < 0) break
            pos = newline + 1
        }
        return out
    }

    private fun splitLine(whole: String, from: Int, to: Int, out: MutableList<IntRange>) {
        val line = whole.substring(from, to)
        if (line.isBlank()) return
        val breaks = BreakIterator.getSentenceInstance(Locale.ROOT)
        breaks.setText(line)
        var a = breaks.first()
        var b = breaks.next()
        while (b != BreakIterator.DONE) {
            trim(whole, from + a, from + b)?.let { out.add(it) }
            a = b
            b = breaks.next()
        }
    }

    private fun trim(s: String, a: Int, endExclusive: Int): IntRange? {
        var i = a
        var j = endExclusive - 1
        while (i <= j && s[i].isWhitespace()) i++
        while (j >= i && s[j].isWhitespace()) j--
        return if (i <= j) i..j else null
    }
}
