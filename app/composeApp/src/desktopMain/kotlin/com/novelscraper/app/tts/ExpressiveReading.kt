package com.novelscraper.app.tts

import com.novelscraper.app.data.ReaderPrefs

/**
 * Expressive reading, a desktop beta: quoted speech in a second voice, pauses
 * that follow the text, and a little pacing. Nothing here is a model; it reads
 * the punctuation the way a narrator would.
 *
 * Quotes are tracked across a chapter, because the sentence split cuts
 * `"Hello. How are you?" she asked.` in two, and forgotten at each paragraph's
 * end (a speech running over paragraphs opens a quote again anyway). Which
 * marks count as dialogue and as stutters, and the dialogue voice, can be set
 * per novel ([NovelVoicing]); the general settings fill in the rest.
 */
object ExpressiveReading : NarrationDirector {

    override fun enabled(bookId: Int): Boolean =
        NovelVoicings.get(bookId).enabled ?: ReaderPrefs.ttsExpressive.value

    // Pauses, in milliseconds at 1x, voice to voice: the voice's own silence is
    // trimmed (DirectedSpeech), so these are the whole gap. Read plainly, Kokoro
    // leaves about 160 ms between sentences and 50 ms at a comma.
    internal const val PAUSE_JOIN = 130        // between two pieces of one sentence
    internal const val PAUSE_SENTENCE = 180
    internal const val PAUSE_PARAGRAPH = 450
    internal const val PAUSE_EXCHANGE = 300    // one line of dialogue answering another
    internal const val PAUSE_TRAILING = 500    // "...", where a sentence ends or within one
    internal const val PAUSE_BROKEN_OFF = 300  // "—", likewise
    internal const val PAUSE_SCENE = 1000      // "***"
    internal const val PAUSE_STUTTER = 170     // after "V-" in "V-Violet"
    internal const val PAUSE_REPEAT = 130      // after the first "that" in "that-that"

    internal const val FASTER = 1.06f   // a short exclamation
    internal const val SLOWER = 0.96f   // a long, descriptive sentence

    private const val QUOTES = "\"“”'‘’「」『』«»"

    /** Where a quote is closed by a dash, not a quote mark. */
    private const val BY_DASH = '—'

    /** What is open at a point in the text: the mark that will close it, and
     *  whether this paragraph speaks by dashes ([Quote.DASH]). */
    internal data class QuoteState(val closer: Char? = null, val dashParagraph: Boolean = false)

    override fun direct(bookId: Int, spoken: List<String?>, paragraphEnd: BooleanArray): List<Direction?> {
        val v = NovelVoicings.get(bookId)
        return direct(spoken, paragraphEnd, v.quotes ?: Quote.AUTOMATIC, v.stutters ?: ALL_MARKS)
    }

    private val ALL_MARKS = StutterMark.entries.toSet()

    internal fun direct(
        spoken: List<String?>,
        paragraphEnd: BooleanArray,
        quotes: Set<Quote> = Quote.AUTOMATIC,
        stutters: Set<StutterMark> = ALL_MARKS,
    ): List<Direction?> {
        var state = QuoteState()
        val pieces = spoken.mapIndexed { i, text ->
            val paragraphStart = i == 0 || paragraphEnd.getOrElse(i - 1) { false }
            val out = text?.let { t ->
                if (t.none { it.isLetterOrDigit() }) emptyList()
                else split(t, state, quotes, paragraphStart).also { (_, after) -> state = after }.first
            }
            if (paragraphEnd.getOrElse(i) { false }) state = QuoteState()
            out
        }
        return spoken.mapIndexed { i, text ->
            val own = pieces[i] ?: return@mapIndexed null
            if (own.isEmpty()) return@mapIndexed Direction(emptyList(), PAUSE_SCENE)
            val speed = speedFor(text!!)
            val next = (i + 1 until spoken.size).firstNotNullOfOrNull { pieces[it]?.takeIf { p -> p.isNotEmpty() } }
            val pause = when {
                paragraphEnd.getOrElse(i) { false } ->
                    if (own.last().dialogue && next?.first()?.dialogue == true) PAUSE_EXCHANGE else PAUSE_PARAGRAPH
                else -> pauseFor(text)
            }
            val beats = own.flatMap { beats(it.text, it.dialogue, speed, stutters) }
            // Pieces of one sentence are never run together; the sentence's own
            // pause follows its last piece, not both.
            val joined = beats.dropLast(1).map { it.copy(pauseAfterMs = maxOf(it.pauseAfterMs, PAUSE_JOIN)) }
            Direction(joined + beats.last().copy(pauseAfterMs = 0), pause)
        }
    }

    // A stutter: up to four letters and one of the novel's stutter marks, once
    // or more, before a word they begin ("V-Violet", "I—I", "that–that"). A
    // trailing "..." or dash with more of the sentence after it. Each is a beat
    // to pause on.
    private val beatRegexes = HashMap<Set<StutterMark>, Regex>()

    private fun beatRegex(marks: Set<StutterMark>): Regex = synchronized(beatRegexes) {
        beatRegexes.getOrPut(marks) {
            val chars = marks.joinToString("") { it.chars }.replace("-", "\\-")
            val stutter = if (chars.isEmpty()) "" else
                """(?<![\p{L}\p{N}'’\-‐‑–—])(?<frags>(?:\p{L}{1,4}[$chars])+)(?<word>\p{L}[\p{L}'’]*)|"""
            Regex(
                stutter +
                    """(?<dots>\.\.\.|…)(?=\s*[\p{L}\p{N}"“'‘«])""" +
                    """|\s*(?<dash>—|–|--|\s-\s)\s*(?=[\p{L}\p{N}"“'‘«])""",
            )
        }
    }

    private val MARK_CHARS = StutterMark.entries.joinToString("") { it.chars }.toCharArray()

    /** How many stutters [text] has, set with [marks]. */
    internal fun countStutters(text: String, marks: Set<StutterMark>): Int =
        beatRegex(marks).findAll(text).count { m ->
            val word = m.groups["word"]?.value ?: return@count false
            isStutter(m.groups["frags"]!!.value.split(*MARK_CHARS).filter { it.isNotEmpty() }, word)
        }

    // "re-read", "co-op": a prefix and a hyphen, not a stammer.
    private val PREFIXES = setOf("re", "pre", "de", "co", "un", "non", "sub", "ex", "bi", "tri", "mis", "anti", "semi")

    /** [text] cut at its stutters, ellipses and dashes, a pause after each. */
    internal fun beats(
        text: String,
        dialogue: Boolean,
        speed: Float,
        marks: Set<StutterMark> = ALL_MARKS,
    ): List<Piece> {
        val out = ArrayList<Piece>()
        val rest = StringBuilder()
        /** Speak what has gathered, then pause; a pause with nothing to say
         *  lengthens the one before. */
        fun say(pause: Int) {
            val t = rest.toString().trim()
            rest.clear()
            when {
                t.any { it.isLetterOrDigit() } -> out.add(Piece(t, dialogue, speed, pause))
                out.isNotEmpty() -> out[out.size - 1] = out.last().let { it.copy(pauseAfterMs = maxOf(it.pauseAfterMs, pause)) }
            }
        }
        var at = 0
        for (m in beatRegex(marks).findAll(text)) {
            val word = m.groups["word"]?.value
            val frags = m.groups["frags"]?.value?.split(*MARK_CHARS)?.filter { it.isNotEmpty() }
            if (word != null && frags != null && !isStutter(frags, word)) continue
            rest.append(text, at, m.range.first)
            at = m.range.last + 1
            when {
                word != null && frags != null -> {
                    // A part-word is cut from the word itself, so it stands
                    // alone; a whole word repeated reads on from what came before.
                    for (f in frags) {
                        if (f.equals(word, ignoreCase = true)) {
                            rest.append(word)
                            say(PAUSE_REPEAT)
                        } else {
                            say(0)
                            out.add(Piece(word, dialogue, speed, PAUSE_STUTTER, onset = f.length.toFloat() / word.length))
                        }
                    }
                    rest.append(word)
                }
                m.groups["dots"] != null -> { rest.append(m.value); say(PAUSE_TRAILING) }
                else -> say(PAUSE_BROKEN_OFF)
            }
        }
        rest.append(text, at, text.length)
        say(0)
        return out.ifEmpty { listOf(Piece(text.trim(), dialogue, speed)) }
    }

    /** Every fragment begins [word] (or is it), and it isn't a prefix like "re-". */
    private fun isStutter(frags: List<String>, word: String): Boolean {
        if (frags.size == 1 && frags[0].lowercase() in PREFIXES && frags[0].first().isLowerCase()) return false
        return frags.all { f -> word.startsWith(f, ignoreCase = true) }
    }

    /**
     * [text] cut at the novel's dialogue marks ([quotes]), starting from [state];
     * also returns what is still open after it. [paragraphStart] says whether a
     * dash here can open a paragraph of dialogue.
     */
    internal fun split(
        text: String,
        state: QuoteState = QuoteState(),
        quotes: Set<Quote> = Quote.AUTOMATIC,
        paragraphStart: Boolean = false,
    ): Pair<List<Piece>, QuoteState> {
        val out = ArrayList<Piece>()
        var closer = state.closer
        val dashParagraph = state.dashParagraph ||
            (paragraphStart && Quote.DASH in quotes && text.trimStart().firstOrNull().let { it == '—' || it == '–' })
        val run = StringBuilder()
        fun flush() {
            val t = run.toString().trim()
            if (t.isNotEmpty()) out.add(Piece(t, closer != null))
            run.clear()
        }
        for (k in text.indices) {
            val c = text[k]
            val before = text.getOrNull(k - 1)
            val after = text.getOrNull(k + 1)
            val open = closer
            if (open != null) {
                val closes = when (open) {
                    BY_DASH -> c == '—' || c == '–'
                    // An apostrophe inside a word ("don't") closes nothing.
                    '\'', '’' -> c == open && (after == null || !after.isLetterOrDigit())
                    else -> c == open
                }
                when {
                    !closes -> run.append(c)
                    // The dash that ends a line of dialogue begins the narration.
                    open == BY_DASH -> { flush(); closer = null; run.append(c) }
                    else -> { run.append(c); flush(); closer = null }
                }
            } else {
                val opens = opener(c, before, after, quotes, dashParagraph)
                if (opens != null) { flush(); closer = opens }
                run.append(c)
            }
        }
        flush()
        return merge(out) to QuoteState(closer, dashParagraph)
    }

    /** The mark that will close a quote [c] opens here, or null if it opens none. */
    private fun opener(c: Char, before: Char?, after: Char?, quotes: Set<Quote>, dashParagraph: Boolean): Char? {
        val wordStart = before == null || !before.isLetterOrDigit()
        return when {
            c == '"' && Quote.DOUBLE_STRAIGHT in quotes -> '"'
            c == '“' && Quote.DOUBLE_CURLY in quotes -> '”'
            c == '\'' && Quote.SINGLE_STRAIGHT in quotes && wordStart && after?.isLetter() == true -> '\''
            c == '‘' && Quote.SINGLE_CURLY in quotes && wordStart -> '’'
            c == '「' && Quote.CORNER in quotes -> '」'
            c == '『' && Quote.WHITE_CORNER in quotes -> '』'
            c == '«' && Quote.GUILLEMETS in quotes -> '»'
            (c == '—' || c == '–') && dashParagraph -> BY_DASH
            else -> null
        }
    }

    /** Pieces with under two letters (a stray `",` or `—`) join a neighbour,
     *  since a voice given almost nothing garbles it; then neighbours in the
     *  same voice join up. */
    private fun merge(pieces: List<Piece>): List<Piece> {
        if (pieces.size <= 1) return pieces
        val out = ArrayList<Piece>()
        var carry = ""
        for (p in pieces) {
            val text = if (carry.isEmpty()) p.text else "$carry ${p.text}"
            if (p.text.count { it.isLetter() } < 2) {
                val last = out.lastOrNull()
                if (last != null) out[out.size - 1] = Piece("${last.text} ${p.text}", last.dialogue)
                else carry = text
                continue
            }
            carry = ""
            val last = out.lastOrNull()
            if (last != null && last.dialogue == p.dialogue) out[out.size - 1] = Piece("${last.text} $text", p.dialogue)
            else out.add(Piece(text, p.dialogue))
        }
        if (carry.isNotEmpty()) out.add(Piece(carry, pieces.last().dialogue))
        return out
    }

    internal fun speedFor(sentence: String): Float {
        val words = sentence.split(Regex("\\s+")).count { w -> w.any { it.isLetterOrDigit() } }
        val end = sentence.trimEnd { it.isWhitespace() || it in QUOTES }
        return when {
            words <= 6 && end.endsWith('!') -> FASTER
            words > 30 -> SLOWER
            else -> 1f
        }
    }

    /** The pause after a sentence that does not end its paragraph. */
    internal fun pauseFor(sentence: String): Int {
        val end = sentence.trimEnd { it.isWhitespace() || it in QUOTES }
        return when {
            end.endsWith('…') || end.endsWith("...") -> PAUSE_TRAILING
            end.endsWith('—') || end.endsWith('–') || end.endsWith('-') -> PAUSE_BROKEN_OFF
            else -> PAUSE_SENTENCE
        }
    }

    // The best-sounding voice of each kind per accent, by Kokoro's own grading.
    private val PREFERRED = mapOf(
        "American English" to mapOf("Female" to "af_heart", "Male" to "am_michael"),
        "British English" to mapOf("Female" to "bf_emma", "Male" to "bm_george"),
    )

    /** The novel's own dialogue voice, else the general one, else one picked to go with the narrator. */
    override fun dialogueSpeaker(bookId: Int, engine: String, speaker: Int): Int {
        val v = NovelVoicings.get(bookId)
        return when (engine) {
            ReaderPrefs.ENGINE_KOKORO -> (v.kokoroDialogue ?: ReaderPrefs.kokoroDialogueSpeaker.value)
                .takeIf { KokoroVoices.voice(it) != null } ?: kokoroPartner(speaker)
            ReaderPrefs.ENGINE_SUPERTONIC -> (v.supertonicDialogue ?: ReaderPrefs.supertonicDialogueSpeaker.value)
                .takeIf { it in TtsModels.SUPERTONIC_VOICES.indices } ?: supertonicPartner(speaker)
            else -> speaker   // Piper has one voice per model
        }
    }

    /** A voice of the other gender in the narrator's language, else another of the same. */
    internal fun kokoroPartner(speaker: Int): Int {
        val me = KokoroVoices.voice(speaker) ?: return speaker
        val same = KokoroVoices.all.filter { it.language == me.language && it.id != me.id }
        val other = same.filter { it.gender.isNotEmpty() && it.gender != me.gender }
        val preferred = PREFERRED[me.language]?.get(other.firstOrNull()?.gender)
        return (other.firstOrNull { it.code == preferred } ?: other.firstOrNull() ?: same.firstOrNull())?.id ?: speaker
    }

    /** Supertonic's voices are five female then five male. */
    internal fun supertonicPartner(speaker: Int): Int {
        val n = TtsModels.SUPERTONIC_VOICES.size
        return if (speaker in 0 until n) (speaker + n / 2) % n else speaker
    }
}
