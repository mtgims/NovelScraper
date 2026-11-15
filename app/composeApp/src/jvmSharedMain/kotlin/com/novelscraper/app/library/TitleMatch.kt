package com.novelscraper.app.library

import java.text.Normalizer

/**
 * Whether a title found on another source is the novel being moved.
 *
 * Only an [Kind.Exact] match is taken without asking: a migration of many
 * novels at once deletes downloads and reaches the other devices, so a wrong
 * guess costs. Exact means the same words, whatever the case, accents,
 * punctuation, "&" for "and", or a leading "The". A [Kind.Near] title (a word
 * apart, or cut short by the site's list) is offered for the reader to confirm.
 * A title with a number the other lacks is a sequel or a volume, not a near
 * miss, and so is anything else further apart: [Kind.None].
 */
internal object TitleMatch {
    enum class Kind { Exact, Near, None }

    private const val NEAR = 0.8
    private const val SHORTEST_CUT = 8

    fun of(wanted: String, found: String): Kind {
        val a = normalize(wanted)
        if (a.isEmpty()) return Kind.None
        val cut = found.trimEnd().let { it.endsWith("...") || it.endsWith("…") }
        val b = normalize(found)
        if (b.isEmpty()) return Kind.None
        if (cut) return if (b.length >= SHORTEST_CUT && a.startsWith(b) && a != b) Kind.Near else Kind.None
        if (a == b) return Kind.Exact
        if (numbers(a) != numbers(b)) return Kind.None
        return if (similarity(a, b) >= NEAR) Kind.Near else Kind.None
    }

    fun normalize(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .removePrefix("the ")

    private fun numbers(s: String) = s.split(' ').filter { t -> t.any(Char::isDigit) }.toSet()

    private fun similarity(a: String, b: String): Double =
        1.0 - distance(a, b).toDouble() / maxOf(a.length, b.length)

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
