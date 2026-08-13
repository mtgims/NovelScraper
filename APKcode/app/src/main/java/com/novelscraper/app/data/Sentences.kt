package com.novelscraper.app.data

import androidx.core.text.HtmlCompat

/**
 * Shared sentence splitting so the reader (highlight / tap-to-start) and the
 * TtsService (what gets spoken) agree on sentence *indices*. Both derive from
 * the same plain-text form of the chapter HTML and the same split rule.
 */
object Sentences {
    private val SPLIT = Regex("(?<=[.!?。！？])\\s+|\\n+")

    fun plain(html: String): String =
        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

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
