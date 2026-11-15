package com.novelscraper.app.library

/** Words counted in some read chapters ([LibraryStore.wordsRead]), to estimate the rest from. */
data class WordSample(val words: Long, val chapters: Int) {
    operator fun plus(o: WordSample) = WordSample(words + o.words, chapters + o.chapters)

    companion object { val NONE = WordSample(0, 0) }
}

/** One novel's place in its reading. */
data class NovelProgress(val book: LibBook, val read: Int, val total: Int) {
    val fraction: Float get() = if (total == 0) 0f else read.toFloat() / total
    val left: Int get() = (total - read).coerceAtLeast(0)
}

/**
 * The library's reading, summed up for the statistics page and its export, so
 * the two never disagree.
 *
 * Every novel falls in one of four states: **finished** (every chapter read,
 * and the novel itself is complete), **caught up** (every chapter read of one
 * still coming out), **reading** (some read) and **not started**.
 */
data class ReadingStats(
    val novels: Int,
    val chapters: Int,
    val chaptersRead: Int,
    val finished: Int,
    val caughtUp: Int,
    val reading: Int,
    val notStarted: Int,
    val downloaded: Int,
    /** Chapters read per source, most first. */
    val bySource: List<Pair<String, Int>>,
    /** Novels per genre, most first. */
    val byGenre: List<Pair<String, Int>>,
    /** How many novels have each rating, 1 to 5 stars. */
    val ratings: List<Int>,
    /** Started and not done, the one opened last first. */
    val continueReading: List<NovelProgress>,
    val progress: List<NovelProgress>,
) {
    val rated: Int get() = ratings.sum()
    val averageRating: Float get() = if (rated == 0) 0f else ratings.mapIndexed { i, n -> (i + 1) * n }.sum().toFloat() / rated
    val started: Int get() = finished + caughtUp + reading
    val percentRead: Float get() = if (chapters == 0) 0f else chaptersRead * 100f / chapters

    /**
     * Words read, from [sample]: the words per chapter in the read chapters whose
     * text is still here, times every chapter read. Streamed text is pruned, so
     * counting only what is left would fall further behind with every chapter.
     */
    fun wordsRead(sample: WordSample): Long =
        if (sample.chapters == 0) 0L else (sample.words.toDouble() / sample.chapters * chaptersRead).toLong()

    /** [wordsRead] at an ordinary silent reading speed. */
    fun minutesRead(sample: WordSample): Long = wordsRead(sample) / WORDS_PER_MINUTE

    companion object {
        const val WORDS_PER_MINUTE = 250L
        private val COMPLETE = Regex("""\b(complete|completed|finished|end|ended)\b""", RegexOption.IGNORE_CASE)

        fun of(books: List<LibBook>): ReadingStats {
            val progress = books.map { NovelProgress(it, (it.chapterCount - it.unreadCount).coerceAtLeast(0), it.chapterCount) }
            var finished = 0; var caughtUp = 0; var reading = 0; var notStarted = 0
            for (p in progress) when {
                p.total > 0 && p.read >= p.total -> if (COMPLETE.containsMatchIn(p.book.status)) finished++ else caughtUp++
                p.read > 0 -> reading++
                else -> notStarted++
            }
            val bySource = progress.filter { it.read > 0 }
                .groupBy { it.book.site.ifBlank { "Other" } }
                .map { (site, list) -> site to list.sumOf { it.read } }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            val byGenre = books.flatMap { b -> b.genres.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() } }
                .groupBy { it.lowercase() }
                .map { (_, names) -> names.first() to names.size }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            val ratings = (1..5).map { star -> books.count { it.rating == star } }
            return ReadingStats(
                novels = books.size,
                chapters = progress.sumOf { it.total },
                chaptersRead = progress.sumOf { it.read },
                finished = finished, caughtUp = caughtUp, reading = reading, notStarted = notStarted,
                downloaded = books.sumOf { it.downloadedCount },
                bySource = bySource,
                byGenre = byGenre,
                ratings = ratings,
                continueReading = progress.filter { it.read > 0 && it.read < it.total }.sortedByDescending { it.book.openedAt },
                progress = progress,
            )
        }
    }
}
