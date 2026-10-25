package com.novelscraper.app.tts

import kotlinx.serialization.Serializable

/**
 * One of a novel's characters who speaks in a voice of their own. Found in its
 * stored chapters ([CastReading.detect]), kept with the novel's settings.
 */
@Serializable
data class CastMember(
    val name: String,
    /** Other names that are the same person: "Tau" for "Tau-indi". */
    val aliases: List<String> = emptyList(),
    /** "Female" or "Male", as voices are labelled; null where the text didn't say. */
    val gender: String? = null,
    /** Lines found attributed to them, which ranks the cast. */
    val lines: Int = 0,
    /** Their voice on each engine; null: the dialogue voice. */
    val kokoro: Int? = null,
    val supertonic: Int? = null,
) {
    val names get() = listOf(name) + aliases
}

/**
 * Who says a line of dialogue, read the way a listener would: from the
 * narration touching it. Only the novel's cast is ever named, and anything
 * unclear is nobody (the dialogue voice): a wrong voice is worse than none.
 *
 * Measured on stored novels (2026-10-25), the speaker is named beside about a
 * quarter of dialogue paragraphs and a pronoun beside another sixth; a third
 * have no narration at all. Picking any name in the paragraph got about one
 * in four wrong, so only the sentence touching the line counts.
 */
object CastReading {

    /** Characters with voices of their own; everyone else is the dialogue voice. */
    const val MAX_CAST = 6

    /** Lines a name needs before it joins the cast. */
    const val MIN_LINES = 3

    /** Paragraphs back that a "he" or "she" can reach for its person. */
    private const val PRONOUN_REACH = 3

    private const val VERBS = "said|says|asked|asks|replied|replies|answered|answers|shouted|shouts|yelled|" +
        "screamed|whispered|whispers|murmured|murmurs|muttered|mutters|called|cried|snapped|growled|hissed|" +
        "added|continued|demanded|insisted|admitted|agreed|breathed|offered|interrupted|repeated|declared|" +
        "announced|grunted|groaned|warned|explained|suggested|protested|roared|barked|sneered|chuckled|" +
        "laughed|sighed|stammered|stuttered|pleaded|begged|exclaimed|retorted|responded|remarked|whimpered|" +
        "bellowed|shrieked|rasped|croaked|grumbled|complained|teased|corrected|managed|started|finished|" +
        "spoke|speaks"

    // A name: one or two capitalised words ("Hector", "Tain Shir", "Tau-indi").
    private const val NAME = """\p{Lu}[\p{L}'’\-]*(?:\s\p{Lu}[\p{L}'’\-]*)?"""
    private const val SUBJECT = """$NAME|[Hh]e|[Ss]he|[Tt]hey|I|[Tt]he\s\p{Ll}+(?:\s\p{Ll}+)?"""

    // Right after a line: "…,” Orin growled", "…,” said Lemon", "…,” the kid said".
    private val TAG = Regex("""^\s*(?<who>$SUBJECT)\s+(?:\p{Ll}+ly\s+)?(?:$VERBS)\b""")
    private val TAG_INVERTED = Regex("""^\s*(?:$VERBS)\s+(?<who>$NAME)\b""")
    // The sentence before a line: someone in it speaking, the nearest one
    // ("When he saw it, Orin cried,", "Hector stared as Lemon spoke."), else
    // whoever it opens on ("Lemon narrowed her eyes.").
    private val SPEAKS = Regex("""(?<who>$SUBJECT)\s+(?:\p{Ll}+ly\s+)?(?:$VERBS)\b""")
    private val OPENS = Regex(
        """^\s*(?:(?:Then|And|But|So|Finally|Instead|Still|Now|Suddenly|Slowly|Quietly),?\s+)?(?<who>$SUBJECT)\s+\p{Ll}""",
    )
    private val ANY_VERB = Regex("""\b(?:$VERBS)\b""")
    private val SENTENCE_END = Regex("""(?<=[.!?…])\s+""")

    /** Who a clause names as its subject: a name, "he"/"she", or someone else (null). */
    private sealed interface Who {
        data class Named(val name: String) : Who
        data class Pronoun(val gender: String) : Who
        data object Other : Who
    }

    private fun who(word: String): Who = when {
        word == "he" || word == "He" -> Who.Pronoun("Male")
        word == "she" || word == "She" -> Who.Pronoun("Female")
        word.first().isLowerCase() || word.startsWith("The ") || word in NOT_NAMES -> Who.Other
        word.endsWith("’s") || word.endsWith("'s") -> Who.Other
        else -> Who.Named(word)
    }

    // Capitalised words that open sentences and are nobody.
    private val NOT_NAMES = setOf(
        "The", "A", "An", "I", "They", "We", "You", "It", "This", "That", "There", "Then", "But", "And", "Or", "So", "Yes", "No",
        "Oh", "What", "Why", "How", "Who", "Where", "When", "If", "As", "Not", "Now", "Here", "His", "Her",
        "Their", "My", "Our", "Your", "Its", "Well", "Still", "Just", "Even", "After", "Before", "Because",
        "While", "Though", "Maybe", "Perhaps", "All", "Someone", "Somebody", "Nobody", "Everyone", "Again",
    )

    /** Who a paragraph's [pieces] say speaks its dialogue, from the narration touching its first line. */
    private fun speakerIn(pieces: List<Piece>): Who? {
        val first = pieces.indexOfFirst { it.role == Role.DIALOGUE }
        if (first < 0) return null
        pieces.getOrNull(first + 1)?.takeIf { it.role == Role.NARRATOR }?.text?.let { after ->
            (TAG.find(after) ?: TAG_INVERTED.find(after))?.let { return who(it.groups["who"]!!.value) }
            // A tag naming someone it can't make out ("the functionary at her
            // side explained"): not whoever the sentence before was about.
            if (ANY_VERB.containsMatchIn(after.trim().split(SENTENCE_END).first())) return Who.Other
        }
        pieces.getOrNull(first - 1)?.takeIf { it.role == Role.NARRATOR }?.text?.let { before ->
            val last = before.trim().split(SENTENCE_END).last()
            (SPEAKS.findAll(last).lastOrNull() ?: OPENS.find(last))?.let { return who(it.groups["who"]!!.value) }
        }
        return null
    }

    /** The member [name] is, by name or alias, or a title before one ("Captain Hector"). */
    private fun member(name: String, cast: List<CastMember>): CastMember? =
        cast.firstOrNull { m -> m.names.any { it == name } }
            ?: cast.firstOrNull { m -> m.names.any { name.endsWith(" $it") } }

    private val nameRegexes = HashMap<String, Regex>()

    private fun mentions(text: String, m: CastMember): Boolean = m.names.any { n ->
        synchronized(nameRegexes) {
            nameRegexes.getOrPut(n) { Regex("""(?<![\p{L}\-])${Regex.escape(n)}(?![\p{L}\-])""") }
        }.containsMatchIn(text)
    }

    /**
     * Each paragraph's speaker from [cast], aligned with [paragraphs] (each a
     * paragraph's pieces; an empty one is a scene break). A pronoun is the one
     * member of that gender named in the last few paragraphs, if only one was.
     */
    fun speakers(paragraphs: List<List<Piece>>, cast: List<CastMember>): List<String?> {
        if (cast.isEmpty()) return paragraphs.map { null }
        val lastSeen = HashMap<String, Int>()
        return paragraphs.mapIndexed { k, pieces ->
            if (pieces.isEmpty()) { lastSeen.clear(); return@mapIndexed null }
            val narration = pieces.filter { it.role != Role.DIALOGUE }.joinToString(" ") { it.text }
            for (m in cast) if (mentions(narration, m)) lastSeen[m.name] = k
            val speaker = when (val w = speakerIn(pieces)) {
                is Who.Named -> member(w.name, cast)
                // Someone outside the cast acting here could be the "she".
                is Who.Pronoun -> if (othersAct(narration, cast)) null else cast.filter { m ->
                    m.gender == w.gender && lastSeen[m.name]?.let { it >= k - PRONOUN_REACH } == true
                }.singleOrNull()
                else -> null
            }
            speaker?.name?.also { lastSeen[it] = k }
        }
    }

    /** Whether a sentence of [narration] opens on a name outside [cast] ("Mother Pinion took her…"). */
    private fun othersAct(narration: String, cast: List<CastMember>): Boolean =
        narration.trim().split(SENTENCE_END).any { sentence ->
            val w = OPENS.find(sentence)?.let { who(it.groups["who"]!!.value) }
            w is Who.Named && member(w.name, cast) == null
        }

    // --- finding the cast --------------------------------------------------------

    // A capitalised word or two mid-sentence: how real names are told from
    // words that only open sentences.
    private val MID_SENTENCE = Regex("""[\p{Ll},;:]\s+(\p{Lu}[\p{L}'’\-]*)(?:\s(\p{Lu}[\p{L}'’\-]*))?""")
    private val HE = Regex("""\b(?:he|him|his|himself)\b""", RegexOption.IGNORE_CASE)
    private val SHE = Regex("""\b(?:she|her|hers|herself)\b""", RegexOption.IGNORE_CASE)

    /**
     * The characters who speak most in [htmls] (stored chapters), up to
     * [MAX_CAST], each with how many lines were theirs and a gender where the
     * narration around them says. No voices yet ([assignKokoro]).
     */
    fun detect(htmls: List<String>, quotes: Set<Quote> = Quote.AUTOMATIC): List<CastMember> {
        val lines = HashMap<String, Int>()
        val he = HashMap<String, Int>()
        val she = HashMap<String, Int>()
        val nextHe = HashMap<String, Int>()
        val nextShe = HashMap<String, Int>()
        val text = StringBuilder()
        for (html in htmls) {
            val plain = VoicingDetector.plainText(html)
            text.append(plain).append('\n')
            for (paragraph in plain.lines()) {
                if (paragraph.isBlank()) continue
                val pieces = ExpressiveReading.split(paragraph, quotes = quotes, paragraphStart = true).first
                followedByPronoun(pieces, nextHe, nextShe)
                val w = speakerIn(pieces) as? Who.Named ?: continue
                lines.merge(w.name, 1, Int::plus)
                val narration = pieces.filter { it.role == Role.NARRATOR }.joinToString(" ") { it.text }
                he.merge(w.name, HE.findAll(narration).count(), Int::plus)
                she.merge(w.name, SHE.findAll(narration).count(), Int::plus)
            }
        }
        // A real name turns up capitalised mid-sentence too; "While" and
        // "Shaking", caught opening sentences, never do.
        val all = text.toString()
        val mid = HashMap<String, Int>()
        for (m in MID_SENTENCE.findAll(all)) {
            val (first, second) = m.destructured
            mid.merge(first, 1, Int::plus)
            if (second.isNotEmpty()) mid.merge("$first $second", 1, Int::plus)
        }
        val named = lines.filterKeys { (mid[it] ?: 0) >= 2 }
        // "Tau" and "Tau-indi", "Shao" and "Shao Lune": one person, under the
        // name used more.
        val merged = LinkedHashMap<String, MutableList<String>>()
        for (n in named.keys.sortedByDescending { named.getValue(it) }) {
            val into = merged.keys.firstOrNull { sameName(it, n) }
            if (into == null) merged[n] = mutableListOf() else merged.getValue(into).add(n)
        }
        return merged.map { (name, aliases) ->
            val names = listOf(name) + aliases
            CastMember(name, aliases, lines = names.sumOf { lines[it] ?: 0 })
        }.filter { it.lines >= MIN_LINES }.sortedByDescending { it.lines }.take(MAX_CAST).map { c ->
            // Best told by the sentence after one about them alone ("Lemon
            // smiled. She…") and a possessive straight after their name
            // ("Lemon narrowed her eyes"); else by the pronouns around their lines.
            val (his, her) = possessives(c.names, all)
            val male = his + c.names.sumOf { nextHe[it] ?: 0 }
            val female = her + c.names.sumOf { nextShe[it] ?: 0 }
            c.copy(gender = genderOf(male, female, atLeast = 4)
                ?: genderOf(c.names.sumOf { he[it] ?: 0 }, c.names.sumOf { she[it] ?: 0 }, atLeast = 3))
        }
    }

    private val OTHER_NAME = Regex("""[\p{Ll},;:]\s+\p{Lu}""")

    /**
     * Counts, by name, a sentence opening on "He" or "She" straight after one
     * that opens on that name and names nobody else: "Lemon smiled. She left."
     */
    private fun followedByPronoun(pieces: List<Piece>, he: HashMap<String, Int>, she: HashMap<String, Int>) {
        val sentences = pieces.filter { it.role == Role.NARRATOR }.joinToString(" ") { it.text }
            .trim().split(SENTENCE_END)
        for ((a, b) in sentences.zipWithNext()) {
            val name = (OPENS.find(a)?.let { who(it.groups["who"]!!.value) } as? Who.Named)?.name ?: continue
            if (OTHER_NAME.containsMatchIn(a.substring(name.length))) continue
            when {
                b.startsWith("He ") -> he.merge(name, 1, Int::plus)
                b.startsWith("She ") -> she.merge(name, 1, Int::plus)
            }
        }
    }

    /** "Male" or "Female" where one outnumbers the other two to one, out of [atLeast] or more. */
    private fun genderOf(male: Int, female: Int, atLeast: Int): String? = when {
        male + female < atLeast -> null
        male >= 2 * female -> "Male"
        female >= 2 * male -> "Female"
        else -> null
    }

    /** "Hector shook his head", "Lemon narrowed her eyes": his and her straight after a name and a verb. */
    private fun possessives(names: List<String>, text: String): Pair<Int, Int> {
        var his = 0
        var her = 0
        for (n in names) {
            Regex("""(?<![\p{L}\-])${Regex.escape(n)}\s+\p{Ll}+(?:\s+(?:back|up|down|out|off|over|away))?\s+(his|her|himself|herself)\b""")
                .findAll(text).forEach { if (it.groupValues[1].startsWith("hi")) his++ else her++ }
        }
        return his to her
    }

    private fun sameName(a: String, b: String): Boolean {
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return long.startsWith("$short ") || long.startsWith("$short-") || long.endsWith(" $short")
    }

    /** [found] added to [kept]: those already there keep their voices; new ones join while there is room. */
    fun merge(kept: List<CastMember>, found: List<CastMember>): List<CastMember> {
        val updated = kept.map { k -> found.firstOrNull { f -> f.names.any { it in k.names } }
            ?.let { f -> k.copy(lines = maxOf(k.lines, f.lines), aliases = (k.aliases + f.names - k.name).distinct()) } ?: k }
        val fresh = found.filter { f -> kept.none { k -> f.names.any { it in k.names } } }
        return (updated + fresh).take(maxOf(MAX_CAST, kept.size))
    }

    // --- voices --------------------------------------------------------------------

    // Kokoro's voices best first, by its own grading, per accent and kind.
    private val RANKED = mapOf(
        "American English" to mapOf(
            "Female" to listOf("af_heart", "af_bella", "af_aoede", "af_kore", "af_sarah", "af_nova", "af_alloy", "af_sky", "af_jessica", "af_river"),
            "Male" to listOf("am_michael", "am_fenrir", "am_puck", "am_echo", "am_eric", "am_liam", "am_onyx", "am_adam"),
        ),
        "British English" to mapOf(
            "Female" to listOf("bf_emma", "bf_isabella", "bf_alice", "bf_lily"),
            "Male" to listOf("bm_george", "bm_fable", "bm_lewis", "bm_daniel"),
        ),
    )

    // Never picked for anyone: one whispers, one is Santa.
    private val NEVER = setOf("af_nicole", "am_santa", "pm_santa")

    /**
     * Kokoro voices for the members of [cast] without one: the narrator's
     * language, their gender, best first, none of [taken] (narrator, dialogue,
     * system) nor each other's. Someone whose gender the text didn't make
     * clear keeps the dialogue voice until one is picked for them: a woman
     * in a man's voice is worse than no voice of her own.
     */
    fun assignKokoro(cast: List<CastMember>, narrator: Int, taken: Set<Int>): List<CastMember> {
        val me = KokoroVoices.voice(narrator) ?: return cast
        val used = HashSet(taken + cast.mapNotNull { it.kokoro })
        val ranked = RANKED[me.language].orEmpty()
        fun pool(gender: String) = (ranked[gender].orEmpty().mapNotNull { c -> KokoroVoices.all.firstOrNull { it.code == c } } +
            KokoroVoices.all.filter { it.language == me.language && it.gender == gender })
            .distinct().filter { it.id !in used && it.code !in NEVER }
        return cast.map { m ->
            if (m.kokoro != null || m.gender == null) return@map m
            val v = pool(m.gender).firstOrNull() ?: return@map m
            used.add(v.id)
            m.copy(kokoro = v.id)
        }
    }

    /** The same for Supertonic, whose voices are five female, then five male. */
    fun assignSupertonic(cast: List<CastMember>, taken: Set<Int>): List<CastMember> {
        val n = TtsModels.SUPERTONIC_VOICES.size
        val used = HashSet(taken + cast.mapNotNull { it.supertonic })
        fun pool(gender: String) = (if (gender == "Female") 0 until n / 2 else n / 2 until n).filter { it !in used }
        return cast.map { m ->
            if (m.supertonic != null || m.gender == null) return@map m
            val v = pool(m.gender).firstOrNull() ?: return@map m
            used.add(v)
            m.copy(supertonic = v)
        }
    }
}
