package com.novelscraper.app.tts

import com.novelscraper.app.data.ReaderPrefs

/**
 * What narration says, sentence by sentence.
 *
 * The reader and narration share one sentence split ([com.novelscraper.app.data.Sentences]),
 * and the highlight follows the sentence index, so this never adds or removes
 * sentences: a sentence that is not worth hearing (a site plug, a translator's
 * note) comes back as null and is passed over, and the rest come back with the
 * pronunciation dictionary applied.
 */
object NarrationText {

    /**
     * Lines a reader does not want read aloud: the site's own plugs, patron and
     * chat links, translator credits. Kept deliberately narrow (a whole sentence
     * must look like an aside), so story text is never dropped.
     */
    private val JUNK = listOf(
        // "Royal Road is the home of this novel", "Visit novelfire.net to read..."
        Regex("""\b(royal ?road|novelfire|novelbin|webnovel|wuxiaworld|scribblehub|novelupdates)\b[^.!?]{0,80}\b(read|visit|home|original|support|latest|chapter)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(read|find|continue)[^.!?]{0,40}\b(latest|full|original|more) chapters?\b[^.!?]{0,40}\b(at|on|from)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bp[a@]treon\b|\bko-?fi\b|\bdiscord(\.gg|\s+server)\b|\bbuy me a coffee\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(advance|advanced|early) (access )?chapters?\b""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(translat(or|ed|ion)|editor|proofread(er)?|tl|raw provider|author)('s)?\s*(note|by|:)""", RegexOption.IGNORE_CASE),
        Regex("""\b(please )?(bookmark|follow|rate|review|vote|share) (this|our|us|the) (site|novel|story|page|chapter)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bhttps?://\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bif you (find|encounter) any (errors?|mistakes?|broken links?)""", RegexOption.IGNORE_CASE),
    )

    /** A word or phrase and how it should sound. */
    @kotlinx.serialization.Serializable
    data class Term(val from: String, val to: String, val wholeWord: Boolean = true)

    /** The spoken text for each sentence; null where narration passes over it. */
    fun spokenLines(sentences: List<String>): List<String?> {
        val skipJunk = ReaderPrefs.ttsSkipJunk.value
        val extra = ReaderPrefs.ttsJunkPatterns.value
        val terms = ReaderPrefs.ttsDictionary.value
        return sentences.map { s ->
            if (skipJunk && isJunk(s, extra)) null else say(s, terms).takeIf { it.isNotBlank() }
        }
    }

    /** True if this sentence is an aside rather than the story. */
    fun isJunk(sentence: String, extraPatterns: List<String> = emptyList()): Boolean {
        val s = sentence.trim()
        if (s.isEmpty()) return true
        if (JUNK.any { it.containsMatchIn(s) }) return true
        return extraPatterns.any { p ->
            val t = p.trim()
            t.isNotEmpty() && s.contains(t, ignoreCase = true)
        }
    }

    /** One sentence with the pronunciation dictionary applied. */
    fun say(sentence: String, terms: List<Term> = ReaderPrefs.ttsDictionary.value): String {
        var out = sentence
        for (t in terms) {
            if (t.from.isBlank()) continue
            out = regexFor(t).replace(out) { Regex.escapeReplacement(t.to) }
        }
        return out
    }

    // Cheap cache: the dictionary is small and changes rarely, but a chapter runs
    // every term over every sentence.
    private val cache = HashMap<Term, Regex>()

    private fun regexFor(t: Term): Regex = synchronized(cache) {
        cache.getOrPut(t) {
            val body = Regex.escape(t.from.trim())
            // Word boundaries that also work next to punctuation and non-Latin text.
            val pattern = if (t.wholeWord) """(?<![\p{L}\p{N}])$body(?![\p{L}\p{N}])""" else body
            Regex(pattern, RegexOption.IGNORE_CASE)
        }
    }
}
