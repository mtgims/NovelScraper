package com.novelscraper.app.library

/**
 * Which chapters of a novel on one source are which on another, so that moving
 * it (a migration) carries what was read and where reading left off.
 *
 * Two sites rarely list a novel the same way: one starts with a prologue, one
 * splits long chapters in parts, one skips a chapter, one starts its numbering
 * again every volume. Chapter numbers are what survives most of that, so they
 * are used when both lists have them and they can be trusted: nearly every
 * chapter has one, and they rise through the list (parts of one chapter share
 * a number side by side; a numbering that starts again repeats numbers far
 * apart, and is not trusted). Otherwise chapters are matched by their place in
 * the list, which is a guess, and said to be one ([Plan.exact]).
 */
internal object ChapterMatch {

    /** A chapter as matching sees it. */
    data class Ch(val position: Int, val number: String, val title: String, val read: Boolean)

    /**
     * What the new source's chapters get: [read] positions, and the [resume]
     * position. [exact]: the resume chapter was found as itself, not guessed.
     * [pastTheEnd]: the reader is further on than the new source goes.
     */
    data class Plan(
        val read: Set<Int>,
        val resume: Int?,
        val exact: Boolean,
        val byNumber: Boolean,
        val pastTheEnd: Boolean,
    )

    private val CHAPTER = Regex("""(?i)\b(?:chapter|chap|ch|episode|ep)\.?\s*(\d+(?:\.\d+)?)""")
    private val CJK = Regex("""第\s*(\d+(?:\.\d+)?)""")
    private val LEADING = Regex("""^\s*(\d+(?:\.\d+)?)(?!\d)""")

    /** The chapter's number: the source's own, else one read from its title. */
    fun numberOf(number: String, title: String): Double? =
        number.trim().toDoubleOrNull()
            ?: CHAPTER.find(title)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: CJK.find(title)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: LEADING.find(title)?.groupValues?.get(1)?.toDoubleOrNull()

    /** [old]'s read marks and [resumePosition] (a position in [old]), on [new]. */
    fun plan(old: List<Ch>, new: List<Ch>, resumePosition: Int?): Plan {
        val oldNums = old.sortedBy { it.position }.map { it to numberOf(it.number, it.title) }
        val newNums = new.sortedBy { it.position }.map { it to numberOf(it.number, it.title) }
        return if (trusted(oldNums.map { it.second }) && trusted(newNums.map { it.second }))
            byNumber(oldNums, newNums, resumePosition)
        else byPlace(old, new, resumePosition)
    }

    /** Nearly all numbered, and rising: at most a stray chapter out of place. */
    private fun trusted(numbers: List<Double?>): Boolean {
        if (numbers.isEmpty()) return false
        val known = numbers.filterNotNull()
        if (known.size < numbers.size * 0.8) return false
        val pairs = known.zipWithNext()
        val falls = pairs.count { (a, b) -> b < a }
        // A number met again after others came between: a numbering that
        // starts over. Parts of one chapter sit side by side and don't count.
        val runs = known.fold(mutableListOf<Double>()) { acc, n -> if (acc.lastOrNull() != n) acc += n; acc }
        val repeats = runs.size - runs.toSet().size
        return falls <= pairs.size * 0.02 && repeats <= runs.toSet().size * 0.02
    }

    private fun byNumber(
        old: List<Pair<Ch, Double?>>,
        new: List<Pair<Ch, Double?>>,
        resumePosition: Int?,
    ): Plan {
        val oldNumbers = old.mapNotNull { it.second }.toSet()
        val readNumbers = old.filter { it.first.read }.mapNotNull { it.second }.toSet()
        val at = old.indexOfFirst { it.first.position == resumePosition }
        val resumeCh = old.getOrNull(at)
        // A place in an unnumbered chapter (an interlude, an epilogue) is held
        // by the numbered chapter after it, as its read mark is, or failing
        // that the one before it.
        val numberAfter = if (at < 0) null else old.drop(at).firstNotNullOfOrNull { it.second }
        val numberBefore = if (at < 0) null else old.take(at).lastOrNull { it.second != null }?.second
        val resumeNumber = resumeCh?.second ?: numberAfter ?: numberBefore

        fun readByNumber(n: Double) = n in readNumbers ||
            // A chapter the old source never listed, before the place reached.
            (resumeNumber != null && n < resumeNumber && n !in oldNumbers)

        // A chapter without a number (a prologue, an interlude) goes with the
        // numbered chapter after it.
        val read = HashSet<Int>()
        var nextRead = false
        for ((ch, n) in new.asReversed()) {
            if (n != null) nextRead = readByNumber(n)
            if (nextRead) read += ch.position
        }

        val nearest: Pair<Ch, Double?>? = resumeNumber?.let { r ->
            new.firstOrNull { it.second == r } ?: new.lastOrNull { (it.second ?: Double.MAX_VALUE) < r }
        }
        var exact = nearest != null && nearest.second == resumeNumber
        var resume = nearest
        if (resumeCh != null && resumeCh.second == null && nearest != null && exact) {
            // The unnumbered chapters beside the one holding the place: before
            // the chapter after it, or after the chapter before it.
            val i = new.indexOf(nearest)
            val beside = if (numberAfter != null) {
                new.take(i).takeLastWhile { it.second == null }
            } else {
                new.drop(new.indexOfLast { it.second == resumeNumber } + 1).takeWhile { it.second == null }
            }
            val title = resumeCh.first.title.trim()
            val same = beside.firstOrNull { it.first.title.trim().equals(title, ignoreCase = true) }
            resume = same ?: beside.firstOrNull() ?: nearest
            exact = same != null
        }
        val maxNew = new.mapNotNull { it.second }.maxOrNull()
        return Plan(
            read = read,
            resume = resume?.first?.position,
            exact = exact,
            byNumber = true,
            pastTheEnd = resumeNumber != null && maxNew != null && resumeNumber > maxNew,
        )
    }

    private fun byPlace(old: List<Ch>, new: List<Ch>, resumePosition: Int?): Plan {
        val size = new.maxOfOrNull { it.position } ?: 0
        return Plan(
            read = old.filter { it.read && it.position <= size }.map { it.position }.toSet(),
            resume = resumePosition?.let { minOf(it, size) }?.takeIf { it > 0 },
            exact = false,
            byNumber = false,
            pastTheEnd = resumePosition != null && resumePosition > size,
        )
    }
}
