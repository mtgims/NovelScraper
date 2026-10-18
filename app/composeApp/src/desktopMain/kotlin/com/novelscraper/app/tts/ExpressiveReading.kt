package com.novelscraper.app.tts

import com.novelscraper.app.data.ReaderPrefs

/**
 * Expressive reading, a desktop beta: quoted speech in a second voice, pauses
 * that follow the text, and a little pacing. Nothing here is a model; it reads
 * the punctuation the way a narrator would.
 *
 * Quotes are tracked across a chapter, because the sentence split cuts
 * `"Hello. How are you?" she asked.` in two, and forgotten at each paragraph's
 * end (a speech running over paragraphs opens a quote again anyway). Single
 * quotes are left alone: they are apostrophes far more often than speech.
 */
object ExpressiveReading : NarrationDirector {

    override val enabled: Boolean get() = ReaderPrefs.ttsExpressive.value

    // Pauses, in milliseconds at 1x, added after the voice's own.
    internal const val PAUSE_PARAGRAPH = 250
    internal const val PAUSE_EXCHANGE = 120    // one line of dialogue answering another
    internal const val PAUSE_TRAILING = 350    // "...", where a sentence ends or within one
    internal const val PAUSE_BROKEN_OFF = 180  // "—", likewise
    internal const val PAUSE_SCENE = 700       // "***"
    internal const val PAUSE_STUTTER = 110     // after "V-" in "V-Violet"
    internal const val PAUSE_REPEAT = 70       // after the first "that" in "that-that"

    internal const val FASTER = 1.06f   // a short exclamation
    internal const val SLOWER = 0.96f   // a long, descriptive sentence

    private const val OPEN = "“「『"
    private const val CLOSE = "”」』"
    private const val QUOTES = "\"“”「」『』"

    override fun direct(spoken: List<String?>, paragraphEnd: BooleanArray): List<Direction?> {
        var quoted = false
        val pieces = spoken.mapIndexed { i, text ->
            val out = text?.let { t ->
                if (t.none { it.isLetterOrDigit() }) emptyList()
                else split(t, quoted).also { (_, still) -> quoted = still }.first
            }
            if (paragraphEnd.getOrElse(i) { false }) quoted = false
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
            val beats = own.flatMap { beats(it.text, it.dialogue, speed) }
            // The sentence's own pause follows its last piece, not both.
            Direction(beats.dropLast(1) + beats.last().copy(pauseAfterMs = 0), pause)
        }
    }

    // A stutter: up to four letters and a hyphen or dash (novels set them as
    // "H–help" as often as "H-help"), once or more, before a
    // word they begin ("V-Violet", "I-I-I", "that-that"). A trailing "..." or
    // dash with more of the sentence after it. Each is a beat to pause on.
    private val BEAT = Regex(
        """(?<![\p{L}\p{N}'’\-‐‑–—])(?<frags>(?:\p{L}{1,4}[\-‐‑–—])+)(?<word>\p{L}[\p{L}'’]*)""" +
            """|(?<dots>\.\.\.|…)(?=\s*[\p{L}\p{N}"“'‘])""" +
            """|\s*(?<dash>—|–|--|\s-\s)\s*(?=[\p{L}\p{N}"“'‘])""",
    )

    // "re-read", "co-op": a prefix and a hyphen, not a stammer.
    private val PREFIXES = setOf("re", "pre", "de", "co", "un", "non", "sub", "ex", "bi", "tri", "mis", "anti", "semi")

    /** [text] cut at its stutters, ellipses and dashes, a pause after each. */
    internal fun beats(text: String, dialogue: Boolean, speed: Float): List<Piece> {
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
        for (m in BEAT.findAll(text)) {
            val word = m.groups["word"]?.value
            val frags = m.groups["frags"]?.value?.split('-', '‐', '‑', '–', '—')?.filter { it.isNotEmpty() }
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

    /** [text] cut at its quotes; the second value is whether a quote is still open after it. */
    internal fun split(text: String, quotedAtStart: Boolean): Pair<List<Piece>, Boolean> {
        val out = ArrayList<Piece>()
        var quoted = quotedAtStart
        val run = StringBuilder()
        fun flush() {
            val t = run.toString().trim()
            if (t.isNotEmpty()) out.add(Piece(t, quoted))
            run.clear()
        }
        for (c in text) {
            val opens = c in OPEN || (c == '"' && !quoted)
            val closes = c in CLOSE || (c == '"' && quoted)
            when {
                opens && !quoted -> { flush(); quoted = true; run.append(c) }
                closes && quoted -> { run.append(c); flush(); quoted = false }
                else -> run.append(c)
            }
        }
        flush()
        return merge(out) to quoted
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
            else -> 0
        }
    }

    // The best-sounding voice of each kind per accent, by Kokoro's own grading.
    private val PREFERRED = mapOf(
        "American English" to mapOf("Female" to "af_heart", "Male" to "am_michael"),
        "British English" to mapOf("Female" to "bf_emma", "Male" to "bm_george"),
    )

    override fun dialogueSpeaker(engine: String, speaker: Int): Int = when (engine) {
        ReaderPrefs.ENGINE_KOKORO -> ReaderPrefs.kokoroDialogueSpeaker.value
            .takeIf { KokoroVoices.voice(it) != null } ?: kokoroPartner(speaker)
        ReaderPrefs.ENGINE_SUPERTONIC -> ReaderPrefs.supertonicDialogueSpeaker.value
            .takeIf { it in TtsModels.SUPERTONIC_VOICES.indices } ?: supertonicPartner(speaker)
        else -> speaker   // Piper has one voice per model
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
