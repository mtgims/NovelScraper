package com.novelscraper.app.data

import com.novelscraper.app.platform.htmlToPlain

/**
 * Shared sentence splitting so the reader (highlight / tap-to-start) and the
 * TtsService (what gets spoken) agree on sentence *indices*. Both derive from
 * the same plain-text form of the chapter HTML and the same split rule.
 */
object Sentences {
    private val SPLIT = Regex("(?<=[.!?。！？])\\s+|\\n+")

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

    /** Character ranges (inclusive) of each non-empty sentence in [plain]. */
    fun ranges(plain: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        for (m in SPLIT.findAll(plain)) {
            trim(plain, start, m.range.first)?.let { out.add(it) }
            start = m.range.last + 1
        }
        trim(plain, start, plain.length)?.let { out.add(it) }
        return out
    }

    private fun trim(s: String, a: Int, endExclusive: Int): IntRange? {
        var i = a
        var j = endExclusive - 1
        while (i <= j && s[i].isWhitespace()) i++
        while (j >= i && s[j].isWhitespace()) j--
        return if (i <= j) i..j else null
    }
}
