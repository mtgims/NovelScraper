package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.BookStat
import com.novelscraper.app.data.StatsRead
import com.novelscraper.app.library.Library
import com.novelscraper.app.library.LibBook
import com.novelscraper.app.library.ReadingStats
import com.novelscraper.app.library.WordSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale

sealed interface StatsUi {
    data object Loading : StatsUi
    data class Error(val message: String) : StatsUi
    /** [words] is null while the chapters' words are still being counted. */
    data class Data(val stats: ReadingStats, val words: WordSample?) : StatsUi
}

class StatsViewModel : ViewModel() {
    private val _ui = MutableStateFlow<StatsUi>(StatsUi.Loading)
    val ui: StateFlow<StatsUi> = _ui.asStateFlow()
    private var loading: Job? = null

    /**
     * The library's statistics: what the library itself holds first, then the
     * words, which take reading the text of every stored chapter and so come a
     * moment later. Called each time the page is shown, so reading done since
     * counts; what was shown stays up meanwhile.
     */
    fun load() {
        if (_ui.value !is StatsUi.Data) _ui.value = StatsUi.Loading
        loading?.cancel()
        loading = viewModelScope.launch {
            val books = try {
                withContext(Dispatchers.IO) { Library.store.libraryFlowOnce() }
            } catch (e: Exception) {
                _ui.value = StatsUi.Error("Couldn't load statistics.")
                return@launch
            }
            val stats = ReadingStats.of(books)
            _ui.value = StatsUi.Data(stats, null)
            val words = runCatching { sample(books) }.getOrDefault(WordSample.NONE)
            _ui.value = StatsUi.Data(stats, words)
        }
    }

    /** Words in the read chapters whose text is on this device (downloaded, or kept from reading them). */
    private suspend fun sample(books: List<LibBook>): WordSample = withContext(Dispatchers.IO) {
        books.fold(WordSample.NONE) { acc, b -> acc + Library.store.wordsRead(b.id) }
    }

    /** The reading-progress export ("csv" | "json") as bytes; null on error.
     *  Written here from the library on this device, so it needs no account. */
    suspend fun export(format: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val books = Library.store.libraryFlowOnce()
            val s = exportOf(ReadingStats.of(books), sample(books))
            val text = if (format == "json") toJson(s) else toCsv(s)
            text.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun exportOf(r: ReadingStats, sample: WordSample): StatsRead {
        val words = r.wordsRead(sample).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val hours = r.minutesRead(sample) / 60f
        return StatsRead(
            total_books = r.novels,
            books_started = r.started,
            // Every chapter read, as the export always counted it.
            books_finished = r.finished + r.caughtUp,
            total_chapters = r.chapters,
            chapters_read = r.chaptersRead,
            // Words in unread chapters aren't known without their text.
            total_words = words,
            words_read = words,
            hours_read = hours,
            hours_total = hours,
            hours_remaining = 0f,
            percent_read = r.percentRead,
            books = r.progress.map { p -> BookStat(p.book.id, p.book.title, p.total, p.read, p.fraction * 100f) },
        )
    }

    private fun toCsv(s: StatsRead): String = buildString {
        append("title,total_chapters,read_chapters,percent_read\n")
        for (b in s.books) {
            append(csvField(b.title)).append(',')
            append(b.total_chapters).append(',')
            append(b.read_count).append(',')
            append(String.format(Locale.ROOT, "%.1f", b.percent_read)).append('\n')
        }
    }

    /** RFC 4180: quote when the value holds a comma, quote or newline, and
     *  double any quote inside. */
    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            '"' + value.replace("\"", "\"\"") + '"'
        else value

    private fun toJson(s: StatsRead): String =
        Json { prettyPrint = true }.encodeToString(StatsRead.serializer(), s)
}
