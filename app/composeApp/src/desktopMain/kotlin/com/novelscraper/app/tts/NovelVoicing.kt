package com.novelscraper.app.tts

import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.settingsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a novel marks its dialogue. */
enum class Quote(val label: String) {
    DOUBLE_STRAIGHT("\"Straight\""),
    DOUBLE_CURLY("“Curly”"),
    SINGLE_STRAIGHT("'Single'"),
    SINGLE_CURLY("‘Single curly’"),
    CORNER("「Corner」"),
    WHITE_CORNER("『White corner』"),
    GUILLEMETS("«Guillemets»"),
    /** A paragraph opening on a dash speaks until the next dash ("—Hello —he said."). */
    DASH("— Dash at line start"),
    ;

    companion object {
        /** What expressive reading takes for dialogue unless told otherwise. Single
         *  quotes are left out: they are apostrophes far more often than speech. */
        val AUTOMATIC = setOf(DOUBLE_STRAIGHT, DOUBLE_CURLY, CORNER, WHITE_CORNER, GUILLEMETS)
    }
}

/** What a novel puts between a stutter and the word: "V-Violet", "V–Violet", "V—Violet". */
enum class StutterMark(val label: String, val chars: String) {
    HYPHEN("V-Violet", "-‐‑"),
    EN_DASH("V–Violet", "–"),
    EM_DASH("V—Violet", "—"),
}

/**
 * One novel's expressive reading. Every null follows the general setting
 * (Settings, Narration), so a novel only carries what was changed for it.
 */
@Serializable
data class NovelVoicing(
    /** On or off for this novel; null follows the general switch. */
    val enabled: Boolean? = null,
    val kokoroDialogue: Int? = null,
    val supertonicDialogue: Int? = null,
    /** null: [Quote.AUTOMATIC]. */
    val quotes: Set<Quote>? = null,
    /** null: every mark; empty: no stutters in this novel. */
    val stutters: Set<StutterMark>? = null,
    /** Whispers quieter, shouts louder; null is on. */
    val dynamics: Boolean? = null,
) {
    val isDefault get() = this == NovelVoicing()
}

/** Each novel's [NovelVoicing], kept on this machine by library id. */
object NovelVoicings {
    private val json = Json { ignoreUnknownKeys = true }
    private val store: KeyValueStore by lazy { settingsStore("expressive") }
    private val cache = HashMap<Int, NovelVoicing>()

    fun get(bookId: Int): NovelVoicing = synchronized(cache) {
        cache.getOrPut(bookId) {
            store.getString(key(bookId), null)
                ?.let { runCatching { json.decodeFromString(NovelVoicing.serializer(), it) }.getOrNull() }
                ?: NovelVoicing()
        }
    }

    fun set(bookId: Int, voicing: NovelVoicing) = synchronized(cache) {
        cache[bookId] = voicing
        if (voicing.isDefault) store.remove(key(bookId))
        else store.putString(key(bookId), json.encodeToString(NovelVoicing.serializer(), voicing))
    }

    private fun key(bookId: Int) = "book_$bookId"
}

/**
 * Which marks a novel actually uses, counted in its stored chapters: a guess
 * to start from, shown with its counts so it can be judged.
 */
object VoicingDetector {

    class Found(
        val quotes: Map<Quote, Int>,
        val stutters: Map<StutterMark, Int>,
        val chapters: Int,
    ) {
        /** The dialogue marks worth turning on: every one seen a few times, single
         *  quotes and dashes only where they outnumber double quotes (a novel in
         *  double quotes still uses single ones for a quote within a quote). */
        val suggestedQuotes: Set<Quote>?
            get() {
                val doubles = (quotes[Quote.DOUBLE_STRAIGHT] ?: 0) + (quotes[Quote.DOUBLE_CURLY] ?: 0)
                val picked = quotes.filter { (q, n) ->
                    n >= 3 && when (q) {
                        Quote.SINGLE_STRAIGHT, Quote.SINGLE_CURLY, Quote.DASH -> n > doubles
                        else -> true
                    }
                }.keys
                return picked.ifEmpty { null }
            }

        /** The stutter marks seen; null (every mark) when there were none to go by. */
        val suggestedStutters: Set<StutterMark>?
            get() = stutters.filterValues { it > 0 }.keys.ifEmpty { null }
    }

    private val TAG = Regex("<[^>]+>")
    private val ENTITY = Regex("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);")

    fun detect(htmls: List<String>): Found {
        val quotes = HashMap<Quote, Int>()
        val stutters = HashMap<StutterMark, Int>()
        for (html in htmls) {
            // Paragraph breaks become lines, so a dash paragraph can be told.
            val text = unescape(html.replace(Regex("(?i)</p>|<br\\s*/?>"), "\n").replace(TAG, ""))
            for (q in Quote.entries) quotes.merge(q, count(text, q), Int::plus)
            for (m in StutterMark.entries) stutters.merge(m, ExpressiveReading.countStutters(text, setOf(m)), Int::plus)
        }
        return Found(quotes.filterValues { it > 0 }, stutters, htmls.size)
    }

    // A quote opens where a word starts: after a space, a line or a bracket.
    private fun count(text: String, q: Quote): Int = when (q) {
        Quote.DOUBLE_STRAIGHT -> text.count { it == '"' } / 2
        Quote.DOUBLE_CURLY -> text.count { it == '“' }
        Quote.SINGLE_STRAIGHT -> Regex("""(?<![\p{L}\p{N}])'(?=\p{L})""").findAll(text).count()
        Quote.SINGLE_CURLY -> Regex("""(?<![\p{L}\p{N}])‘(?=\p{L})""").findAll(text).count()
        Quote.CORNER -> text.count { it == '「' }
        Quote.WHITE_CORNER -> text.count { it == '『' }
        Quote.GUILLEMETS -> text.count { it == '«' }
        Quote.DASH -> Regex("""(?m)^\s*[—–]\s*\p{L}""").findAll(text).count()
    }

    private fun unescape(s: String) = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> when (e) {
                "quot" -> "\""; "apos" -> "'"; "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "nbsp" -> " "
                "ldquo" -> "“"; "rdquo" -> "”"; "lsquo" -> "‘"; "rsquo" -> "’"
                "ndash" -> "–"; "mdash" -> "—"; "hellip" -> "…"; "laquo" -> "«"; "raquo" -> "»"
                else -> m.value
            }
        }
    }
}
