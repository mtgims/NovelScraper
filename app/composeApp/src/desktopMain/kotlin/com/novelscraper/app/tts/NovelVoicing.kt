package com.novelscraper.app.tts

import com.novelscraper.app.library.LibraryStore
import com.novelscraper.app.library.NovelSettings
import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.settingsStore
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Base64

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

/** How a novel marks a LitRPG system message ("[Skill acquired: Stealth]"). */
enum class SystemMark(val label: String, val open: Char, val close: Char) {
    SQUARE("[System]", '[', ']'),
    LENTICULAR("【System】", '【', '】'),
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
    /** null: no system messages. Only paragraphs that are wholly bracketed (a
     *  status window, "[Level up!]") count; names inside a sentence ("I used
     *  [Smite]") stay the narrator's. Off unless chosen: brackets also hold
     *  author notes and tags. */
    val system: Set<SystemMark>? = null,
    val kokoroSystem: Int? = null,
    val supertonicSystem: Int? = null,
    /** The characters who speak in voices of their own ([CastReading]); null: not looked for yet. */
    val cast: List<CastMember>? = null,
    /** How many stored chapters [cast] was last looked for in: it is looked
     *  for again, keeping what it has, once there are twice as many. */
    val castFrom: Int = 0,
) {
    val isDefault get() = this == NovelVoicing()
}

/**
 * Each novel's [NovelVoicing] and its own pronunciations, kept on this machine
 * by the novel's name in sync (so removing and adding it again keeps them) and
 * synced with it to the other desktops ([NovelSettings]). An imported EPUB never
 * syncs and has no such name, so it is kept by library id.
 */
object NovelVoicings : NovelSettings {
    private val json = Json { ignoreUnknownKeys = true }
    private val store: KeyValueStore by lazy { settingsStore("expressive") }
    private val termList = ListSerializer(NarrationText.Term.serializer())

    /** The library novels are looked up in; null until [attach] (and in tests that don't). */
    @Volatile private var library: LibraryStore? = null

    /** Keep novels' settings by their sync names in [store], and sync them through it. */
    fun attach(store: LibraryStore) {
        library = store
        store.novelSettings = this
        synchronized(cache) { names.clear(); cache.clear(); terms.clear() }
    }

    /** Back to keeping everything by library id, for tests. */
    internal fun detach() {
        library?.novelSettings = null
        library = null
        synchronized(cache) { names.clear(); cache.clear(); terms.clear() }
    }

    /** What a novel's settings travel as. */
    @Serializable
    private class Shared(val voicing: NovelVoicing = NovelVoicing(), val terms: List<NarrationText.Term> = emptyList())

    private val names = HashMap<Int, String>()
    private val cache = HashMap<String, NovelVoicing>()
    // A novel's own pronunciations, kept apart from the rest so resetting its
    // expressive reading never takes its word list with it.
    private val terms = HashMap<String, List<NarrationText.Term>>()

    /** Novel [bookId]'s name here: "s<encoded sync key>", or "book_<id>" for one
     *  that never syncs. Ids are never reused, so the answer never changes. */
    private fun name(bookId: Int): String = synchronized(cache) {
        names[bookId] ?: run {
            val lib = library ?: return@run "book_$bookId"
            val key = runCatching { lib.syncKeyOf(bookId) }.getOrNull() ?: return@run "book_$bookId"
            nameOf(key).also { names[bookId] = it; migrate(bookId, it) }
        }
    }

    private fun nameOf(syncKey: String) =
        "s" + Base64.getUrlEncoder().withoutPadding().encodeToString(syncKey.toByteArray())

    private fun syncKeyOf(name: String): String? =
        if (!name.startsWith("s")) null
        else runCatching { String(Base64.getUrlDecoder().decode(name.drop(1))) }.getOrNull()

    /** Settings kept by library id, as 1.2.0 kept them, move to the novel's name. */
    private fun migrate(bookId: Int, name: String) {
        for ((old, new) in listOf("book_$bookId" to voicingKey(name), "terms_$bookId" to termsKey(name))) {
            val v = store.getString(old, null) ?: continue
            if (store.getString(new, null) == null) store.putString(new, v)
            store.remove(old)
        }
    }

    private fun voicingKey(name: String) = if (name.startsWith("book_")) name else "v_$name"
    private fun termsKey(name: String) = if (name.startsWith("book_")) "terms_" + name.removePrefix("book_") else "t_$name"

    fun get(bookId: Int): NovelVoicing = voicing(name(bookId))

    private fun voicing(name: String): NovelVoicing = synchronized(cache) {
        cache.getOrPut(name) {
            store.getString(voicingKey(name), null)
                ?.let { runCatching { json.decodeFromString(NovelVoicing.serializer(), it) }.getOrNull() }
                ?: NovelVoicing()
        }
    }

    fun set(bookId: Int, voicing: NovelVoicing) {
        val name = name(bookId)
        synchronized(cache) { keepVoicing(name, voicing) }
        shared(name)
    }

    fun terms(bookId: Int): List<NarrationText.Term> = termsOf(name(bookId))

    private fun termsOf(name: String): List<NarrationText.Term> = synchronized(cache) {
        terms.getOrPut(name) {
            store.getString(termsKey(name), null)
                ?.let { runCatching { json.decodeFromString(termList, it) }.getOrNull() }
                .orEmpty()
        }
    }

    fun setTerms(bookId: Int, list: List<NarrationText.Term>) {
        val name = name(bookId)
        synchronized(cache) { keepTerms(name, list) }
        shared(name)
    }

    private fun keepVoicing(name: String, voicing: NovelVoicing) {
        cache[name] = voicing
        if (voicing.isDefault) store.remove(voicingKey(name))
        else store.putString(voicingKey(name), json.encodeToString(NovelVoicing.serializer(), voicing))
    }

    private fun keepTerms(name: String, list: List<NarrationText.Term>) {
        val clean = list.filter { it.from.isNotBlank() }.distinctBy { it.from.trim().lowercase() }
        terms[name] = clean
        if (clean.isEmpty()) store.remove(termsKey(name))
        else store.putString(termsKey(name), json.encodeToString(termList, clean))
    }

    /** Tell the other devices, when the novel syncs. */
    private fun shared(name: String) {
        val lib = library ?: return
        val key = syncKeyOf(name) ?: return
        val value = json.encodeToString(Shared.serializer(), Shared(voicing(name), termsOf(name)))
        lib.scope.launch {
            runCatching { lib.noteNovelSettings(key, value) }
                .onFailure { Log.w("NovelVoicings", "could not share settings: ${it.message}") }
        }
    }

    override fun all(): Map<String, String> = synchronized(cache) {
        store.keys().filter { it.startsWith("v_") || it.startsWith("t_") }
            .map { it.drop(2) }.distinct()
            .mapNotNull { name -> syncKeyOf(name)?.let { key ->
                key to json.encodeToString(Shared.serializer(), Shared(voicing(name), termsOf(name)))
            } }
            .toMap()
    }

    override fun received(novelKey: String, value: String) {
        val shared = runCatching { json.decodeFromString(Shared.serializer(), value) }.getOrNull() ?: return
        val name = nameOf(novelKey)
        synchronized(cache) {
            keepVoicing(name, shared.voicing)
            keepTerms(name, shared.terms)
        }
    }
}

/**
 * Which marks a novel actually uses, counted in its stored chapters: a guess
 * to start from, shown with its counts so it can be judged.
 */
object VoicingDetector {

    class Found(
        val quotes: Map<Quote, Int>,
        val stutters: Map<StutterMark, Int>,
        /** Wholly bracketed lines, per mark: what would be read as system messages. */
        val system: Map<SystemMark, Int>,
        /** Bracketed names inside sentences, which stay the narrator's. */
        val inlineBrackets: Int,
        val chapters: Int,
    ) {
        /** The system marks with enough standalone lines to be a novel's messages
         *  rather than the odd note; null (none) otherwise. */
        val suggestedSystem: Set<SystemMark>?
            get() = system.filterValues { it >= 5 }.keys.ifEmpty { null }

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
        val system = HashMap<SystemMark, Int>()
        var inline = 0
        for (html in htmls) {
            val text = plainText(html)
            for (q in Quote.entries) quotes.merge(q, count(text, q), Int::plus)
            for (m in StutterMark.entries) stutters.merge(m, ExpressiveReading.countStutters(text, setOf(m)), Int::plus)
            for (line in text.lines()) {
                for (m in SystemMark.entries) {
                    val n = ExpressiveReading.countBrackets(line, setOf(m))
                    if (n == 0) continue
                    if (ExpressiveReading.isSystemLine(line, setOf(m))) system.merge(m, 1, Int::plus) else inline += n
                }
            }
        }
        return Found(quotes.filterValues { it > 0 }, stutters, system.filterValues { it > 0 }, inline, htmls.size)
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

    /** A chapter's text, one paragraph to a line (so a dash paragraph can be told). */
    internal fun plainText(html: String): String =
        unescape(html.replace(Regex("(?i)</p>|<br\\s*/?>"), "\n").replace(TAG, ""))

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
