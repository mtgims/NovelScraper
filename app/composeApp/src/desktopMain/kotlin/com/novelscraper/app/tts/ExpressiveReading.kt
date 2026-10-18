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

    override fun pronunciations(bookId: Int): List<NarrationText.Term> = NovelVoicings.terms(bookId)

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

    // A whisper is quieter and a touch slower, a shout louder and a touch
    // faster: a level and a pace, not a real whisper, which no voice here can do.
    internal const val WHISPER_GAIN = 0.6f
    internal const val WHISPER_SPEED = 0.94f
    internal const val SHOUT_GAIN = 1.4f
    internal const val SHOUT_SPEED = 1.05f

    internal enum class Tone { PLAIN, WHISPER, SHOUT }

    // How the narration says a line was spoken. Verb forms only where the word
    // also describes things ("a roaring fire" is not a shout).
    private val WHISPERED = Regex(
        """\b(whisper(ed|s|ing)?|murmur(ed|s|ing)?|mutter(ed|s|ing)?|breathed|breathes|""" +
            """under (his|her|their|my|your|its) breath|softly|quietly|hushed)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SHOUTED = Regex(
        """\b(shout(ed|s|ing)?|yell(ed|s|ing)?|scream(ed|s|ing)?|roared|bellow(ed|s|ing)?|""" +
            """shriek(ed|s|ing)?|howled|cried out|cries out)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** How [narration] says the paragraph's lines were spoken: one cue or the other, not both. */
    internal fun toneOf(narration: String): Tone {
        val whisper = WHISPERED.containsMatchIn(narration)
        val shout = SHOUTED.containsMatchIn(narration)
        return when {
            whisper && !shout -> Tone.WHISPER
            shout && !whisper -> Tone.SHOUT
            else -> Tone.PLAIN
        }
    }

    /** A line in capitals ("STOP RIGHT THERE!"): enough letters that it isn't an
     *  acronym. Kokoro reads capitals as words (measured: same length either
     *  way), so the text is left as written. */
    internal fun isCapsShout(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.length >= 6 && letters.count { it.isUpperCase() } >= letters.length * 0.8
    }

    private const val QUOTES = "\"“”'‘’「」『』«»]】"

    /** Where a quote is closed by a dash, not a quote mark. */
    private const val BY_DASH = '—'

    /** What is open at a point in the text: the mark that will close it, and
     *  whether this paragraph speaks by dashes ([Quote.DASH]). */
    internal data class QuoteState(val closer: Char? = null, val dashParagraph: Boolean = false)

    override fun direct(bookId: Int, spoken: List<String?>, paragraphEnd: BooleanArray): List<Direction?> {
        val v = NovelVoicings.get(bookId)
        return direct(
            spoken, paragraphEnd, v.quotes ?: Quote.AUTOMATIC, v.stutters ?: ALL_MARKS, v.dynamics ?: true,
            v.system.orEmpty(),
        )
    }

    private val ALL_MARKS = StutterMark.entries.toSet()

    internal fun direct(
        spoken: List<String?>,
        paragraphEnd: BooleanArray,
        quotes: Set<Quote> = Quote.AUTOMATIC,
        stutters: Set<StutterMark> = ALL_MARKS,
        dynamics: Boolean = true,
        system: Set<SystemMark> = emptySet(),
    ): List<Direction?> {
        var state = QuoteState()
        val pieces = spoken.mapIndexed { i, text ->
            val paragraphStart = i == 0 || paragraphEnd.getOrElse(i - 1) { false }
            val out = text?.let { t ->
                if (t.none { it.isLetterOrDigit() }) emptyList()
                else split(t, state, quotes, paragraphStart, system).also { (_, after) -> state = after }.first
            }
            if (paragraphEnd.getOrElse(i) { false }) state = QuoteState()
            out
        }
        // How each paragraph's lines are spoken, from its narration: one speaker
        // to a paragraph is the convention, so a cue there is about its lines.
        val paragraph = IntArray(spoken.size)
        var current = 0
        for (i in spoken.indices) {
            paragraph[i] = current
            if (paragraphEnd.getOrElse(i) { false }) current++
        }
        val tones: Map<Int, Tone> = if (!dynamics) emptyMap() else spoken.indices.groupBy { paragraph[it] }
            .mapValues { (_, sentences) ->
                toneOf(sentences.flatMap { pieces[it].orEmpty() }.filter { it.role == Role.NARRATOR }.joinToString(" ") { it.text })
            }
        return spoken.mapIndexed { i, text ->
            val own = pieces[i] ?: return@mapIndexed null
            if (own.isEmpty()) return@mapIndexed Direction(emptyList(), PAUSE_SCENE)
            val speed = speedFor(text!!)
            val next = (i + 1 until spoken.size).firstNotNullOfOrNull { pieces[it]?.takeIf { p -> p.isNotEmpty() } }
            val pause = when {
                paragraphEnd.getOrElse(i) { false } ->
                    if (own.last().role == Role.DIALOGUE && next?.first()?.role == Role.DIALOGUE) PAUSE_EXCHANGE
                    else PAUSE_PARAGRAPH
                else -> pauseFor(text)
            }
            val beats = own.flatMap { p ->
                // Only characters whisper and shout; a system message in
                // capitals is how such messages are written.
                val caps = dynamics && p.role == Role.DIALOGUE && isCapsShout(p.text)
                val tone = when {
                    p.role != Role.DIALOGUE -> Tone.PLAIN
                    caps -> Tone.SHOUT
                    else -> tones[paragraph[i]] ?: Tone.PLAIN
                }
                val (gain, pace) = when (tone) {
                    Tone.WHISPER -> WHISPER_GAIN to WHISPER_SPEED
                    Tone.SHOUT -> SHOUT_GAIN to SHOUT_SPEED
                    Tone.PLAIN -> 1f to 1f
                }
                beats(p.text, p.role, speed * pace, stutters, gain)
            }
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
        role: Role,
        speed: Float,
        marks: Set<StutterMark> = ALL_MARKS,
        gain: Float = 1f,
    ): List<Piece> {
        val out = ArrayList<Piece>()
        val rest = StringBuilder()
        /** Speak what has gathered, then pause; a pause with nothing to say
         *  lengthens the one before. */
        fun say(pause: Int) {
            val t = rest.toString().trim()
            rest.clear()
            when {
                t.any { it.isLetterOrDigit() } -> out.add(Piece(t, role, speed, pause, gain = gain))
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
                            out.add(Piece(word, role, speed, PAUSE_STUTTER, onset = f.length.toFloat() / word.length, gain = gain))
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
        return out.ifEmpty { listOf(Piece(text.trim(), role, speed, gain = gain)) }
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
        system: Set<SystemMark> = emptySet(),
    ): Pair<List<Piece>, QuoteState> {
        val out = ArrayList<Piece>()
        var closer = state.closer
        val dashParagraph = state.dashParagraph ||
            (paragraphStart && Quote.DASH in quotes && text.trimStart().firstOrNull().let { it == '—' || it == '–' })
        val run = StringBuilder()
        fun flush() {
            val t = run.toString().trim()
            if (t.isNotEmpty()) out.add(Piece(t, roleOf(closer)))
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
                val opens = opener(c, before, after, quotes, dashParagraph, system)
                if (opens != null) { flush(); closer = opens }
                run.append(c)
            }
        }
        flush()
        return merge(out) to QuoteState(closer, dashParagraph)
    }

    /** Who speaks inside a stretch that [closer] will close: a bracket holds a system message. */
    private fun roleOf(closer: Char?): Role = when {
        closer == null -> Role.NARRATOR
        SystemMark.entries.any { it.close == closer } -> Role.SYSTEM
        else -> Role.DIALOGUE
    }

    /** The mark that will close a quote (or a system message) [c] opens here, or null if it opens none. */
    private fun opener(
        c: Char,
        before: Char?,
        after: Char?,
        quotes: Set<Quote>,
        dashParagraph: Boolean,
        system: Set<SystemMark>,
    ): Char? {
        val wordStart = before == null || !before.isLetterOrDigit()
        system.firstOrNull { it.open == c }?.let { return it.close }
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
                if (last != null) out[out.size - 1] = Piece("${last.text} ${p.text}", last.role)
                else carry = text
                continue
            }
            carry = ""
            val last = out.lastOrNull()
            if (last != null && last.role == p.role) out[out.size - 1] = Piece("${last.text} $text", p.role)
            else out.add(Piece(text, p.role))
        }
        if (carry.isNotEmpty()) out.add(Piece(carry, pieces.last().role))
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

    /**
     * Dialogue: the novel's own voice, else the general one, else one picked to
     * go with the narrator. A system message: the novel's own, else one apart
     * from both. Piper has one voice per model, so everything is the narrator.
     */
    override fun speakerFor(bookId: Int, engine: String, narrator: Int, role: Role): Int {
        if (role == Role.NARRATOR) return narrator
        val v = NovelVoicings.get(bookId)
        val dialogue = dialogueVoice(v, engine, narrator)
        if (role == Role.DIALOGUE) return dialogue
        return when (engine) {
            ReaderPrefs.ENGINE_KOKORO -> v.kokoroSystem?.takeIf { KokoroVoices.voice(it) != null }
                ?: kokoroSystemVoice(narrator, dialogue)
            ReaderPrefs.ENGINE_SUPERTONIC -> v.supertonicSystem?.takeIf { it in TtsModels.SUPERTONIC_VOICES.indices }
                ?: supertonicSystemVoice(narrator)
            else -> narrator
        }
    }

    /** The dialogue voice for [v] (a novel's settings, saved or not) on [engine]. */
    internal fun dialogueVoice(v: NovelVoicing, engine: String, narrator: Int): Int = when (engine) {
        ReaderPrefs.ENGINE_KOKORO -> (v.kokoroDialogue ?: ReaderPrefs.kokoroDialogueSpeaker.value)
            .takeIf { KokoroVoices.voice(it) != null } ?: kokoroPartner(narrator)
        ReaderPrefs.ENGINE_SUPERTONIC -> (v.supertonicDialogue ?: ReaderPrefs.supertonicDialogueSpeaker.value)
            .takeIf { it in TtsModels.SUPERTONIC_VOICES.indices } ?: supertonicPartner(narrator)
        else -> narrator
    }

    // Voices for system messages, best first, per accent: distinct from the
    // usual narrator picks.
    private val SYSTEM_PREFERRED = mapOf(
        "American English" to listOf("af_nicole", "af_bella", "am_puck", "am_fenrir", "af_sarah"),
        "British English" to listOf("bf_isabella", "bm_fable", "bm_george", "bf_emma"),
    )

    /** A system voice in the narrator's language that is neither the narrator nor [dialogue]. */
    internal fun kokoroSystemVoice(narrator: Int, dialogue: Int): Int {
        val me = KokoroVoices.voice(narrator) ?: return narrator
        val taken = setOf(narrator, dialogue)
        val preferred = SYSTEM_PREFERRED[me.language].orEmpty()
            .mapNotNull { code -> KokoroVoices.all.firstOrNull { it.code == code } }
        return (preferred.firstOrNull { it.id !in taken }
            ?: KokoroVoices.all.firstOrNull { it.language == me.language && it.id !in taken })?.id ?: narrator
    }

    /** Two voices along from the narrator, in its own block of five (female, male). */
    internal fun supertonicSystemVoice(narrator: Int): Int {
        val n = TtsModels.SUPERTONIC_VOICES.size
        if (narrator !in 0 until n) return narrator
        val block = n / 2
        return narrator / block * block + (narrator % block + 2) % block
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
