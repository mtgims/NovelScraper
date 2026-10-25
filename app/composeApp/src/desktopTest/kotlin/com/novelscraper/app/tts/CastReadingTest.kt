package com.novelscraper.app.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Character voices: who says each line, finding the cast, giving it voices. */
class CastReadingTest {

    private val hector = CastMember("Hector", gender = "Male")
    private val lemon = CastMember("Lemon", gender = "Female")
    private val orin = CastMember("Orin", gender = "Male")
    private val cast = listOf(hector, lemon, orin)

    /**
     * Who says each paragraph's dialogue, read with [cast]: each paragraph is
     * its sentences; null where it has dialogue nobody is known to say, and
     * paragraphs without dialogue are left out.
     */
    private fun speakers(vararg paragraphs: List<String>, with: List<CastMember> = cast): List<String?> {
        val spoken = paragraphs.flatMap { it }
        val ends = BooleanArray(spoken.size)
        var at = 0
        for (p in paragraphs) { at += p.size; ends[at - 1] = true }
        val directions = ExpressiveReading.direct(spoken, ends, cast = with)
        val out = ArrayList<String?>()
        var i = 0
        for (p in paragraphs) {
            val pieces = (i until i + p.size).flatMap { directions[it]?.pieces.orEmpty() }
            i += p.size
            val dialogue = pieces.filter { it.role == Role.DIALOGUE }
            if (dialogue.isEmpty()) continue
            assertEquals(1, dialogue.map { it.character }.distinct().size, "one speaker to a paragraph")
            out.add(dialogue.first().character)
        }
        return out
    }

    private fun p(vararg sentences: String) = sentences.toList()

    @Test fun aTagAfterTheLineNamesTheSpeaker() {
        assertEquals(listOf("Orin"), speakers(p("“Come on,” Orin growled.")))
        assertEquals(listOf("Hector"), speakers(p("“Two weeks,” Hector repeated, and then he closed the door.")))
        assertEquals(listOf("Lemon"), speakers(p("“You coming?” said Lemon.")))
        assertEquals(listOf("Lemon"), speakers(p("“You coming?” Lemon quietly asked.")))
    }

    @Test fun aSentenceBeforeTheLineNamesTheSpeaker() {
        assertEquals(listOf("Lemon"), speakers(p("Lemon narrowed her eyes.", "“That can happen?”")))
        assertEquals(listOf("Hector"), speakers(p("Hector said, “Not now.”")))
        assertEquals(listOf("Orin"), speakers(p("When he saw the body, Orin cried, “Wait!”")))
    }

    @Test fun aNameInsideTheLineIsNotItsSpeaker() {
        assertEquals(listOf<String?>(null), speakers(p("“Hector, wait!” the kid said.")))
        assertEquals(listOf<String?>(null), speakers(p("“Lemon knows.”")))
    }

    @Test fun someoneOutsideTheCastIsNobody() {
        assertEquals(listOf<String?>(null), speakers(p("“Lies,” the big guy growled.")))
        assertEquals(listOf<String?>(null), speakers(p("“Move,” Grando said.")))
    }

    @Test fun anUnattributedLineIsTheDialogueVoice() {
        assertEquals(listOf("Hector", null), speakers(
            p("“Two weeks,” Hector said."),
            p("“That’s too long.”"),
        ))
    }

    @Test fun aPronounIsTheOnlyOneOfThatKindAround() {
        // Hector and Lemon in the scene: "she" can only be Lemon, "he" only Hector.
        assertEquals(listOf("Lemon", "Hector"), speakers(
            p("Hector looked at Lemon across the table.", "“Eat,” she said."),
            p("“Later,” he said."),
        ))
        // Hector and Orin both there: "he" could be either, so neither.
        assertEquals(listOf<String?>(null), speakers(
            p("Orin stood beside Hector.", "“Move,” he said."),
        ))
    }

    @Test fun aPronounDoesNotReachBackPastAFewParagraphs() {
        assertEquals(listOf<String?>(null), speakers(
            p("Lemon left the room."),
            p("The rain went on."), p("The rain went on."), p("The rain went on."), p("The rain went on."),
            p("“Hello,” she said."),
        ))
    }

    @Test fun aSceneBreakForgetsWhoWasAround() {
        assertEquals(listOf<String?>(null), speakers(
            p("Lemon left the room."),
            p("***"),
            p("“Hello,” she said."),
        ))
    }

    @Test fun anAliasOrATitleStillFindsThem() {
        val tau = CastMember("Tau-indi", aliases = listOf("Tau"), gender = "Male")
        assertEquals(listOf("Tau-indi"), speakers(p("“You stink of meat,” Tau said."), with = listOf(tau)))
        assertEquals(listOf("Hector"), speakers(p("“Get out,” Captain Hector said.")))
    }

    // Each of these was wrong on a real novel before.

    @Test fun aTagNamingSomeoneElseIsNotTheSentenceBefore() {
        assertEquals(listOf<String?>(null), speakers(
            p("Lemon walked the wing.", "“For minor corrections,” the functionary at her side explained."),
        ))
    }

    @Test fun whoeverSpeaksInTheSentenceBeforeIsTheSpeaker() {
        assertEquals(listOf("Lemon"), speakers(p("Hector stared at the art as Lemon spoke.", "“If you think so.”")))
        assertEquals(listOf<String?>(null), speakers(
            p("Hector was expressionless while Bai Ning Bing sighed in disappointment, “These few days.”"),
        ))
    }

    @Test fun aPronounNearSomeoneOutsideTheCastIsNobody() {
        assertEquals(listOf<String?>(null), speakers(
            p("Lemon sat down."),
            p("Mother Pinion took her by the shoulders.", "“You’re strong,” she said."),
        ))
    }

    @Test fun withoutACastNothingChanges() {
        assertEquals(listOf<String?>(null), speakers(p("“Come on,” Orin growled."), with = emptyList()))
    }

    // --- finding the cast -------------------------------------------------------

    private fun chapter(vararg paragraphs: String) = paragraphs.joinToString("") { "<p>$it</p>" }

    @Test fun findsWhoSpeaksMostWithTheirGender() {
        val html = chapter(
            "“Come on,” Orin growled. He kicked the door.",
            "“Fine,” Orin said, and he sat down.",
            "Orin shrugged. “I could break ’em.” He grinned.",
            "“You coming?” Lemon called. She waved from the door.",
            "Lemon narrowed her eyes. “That can happen?” She frowned.",
            "“Eat,” Lemon said, pushing her bowl over.",
            "“Move,” Grando said.",
            "While he spoke, the rain fell on Orin and Lemon and Grando.",
            "“Stop,” While said.", // not a name: never capitalised mid-sentence
        )
        val found = CastReading.detect(listOf(html, html))
        assertEquals(listOf("Lemon", "Orin"), found.map { it.name }.sorted())
        assertEquals("Male", found.first { it.name == "Orin" }.gender)
        assertEquals("Female", found.first { it.name == "Lemon" }.gender)
        assertTrue(found.all { it.lines >= CastReading.MIN_LINES })
    }

    @Test fun aLongerNameTakesTheShortOneAsAnAlias() {
        val lines = (1..4).flatMap { listOf("“Yes,” Tau said.", "“No,” Tau-indi said.") } +
            "Then Tau-indi and Tau left, as Tau-indi and Tau do."
        val found = CastReading.detect(listOf(chapter(*lines.toTypedArray())))
        assertEquals(1, found.size)
        assertEquals(8, found.single().lines)
        assertEquals(setOf("Tau", "Tau-indi"), (found.single().aliases + found.single().name).toSet())
    }

    @Test fun keepsOnlyTheBiggestCast() {
        val names = listOf("Ann", "Bob", "Cid", "Dee", "Eve", "Fay", "Gus", "Hal")
        val html = chapter(*names.flatMapIndexed { k, n ->
            List(3 + k) { "“Hi,” $n said." } + "Then came $n and $n."
        }.toTypedArray())
        val found = CastReading.detect(listOf(html))
        assertEquals(CastReading.MAX_CAST, found.size)
        assertEquals(names.takeLast(CastReading.MAX_CAST).toSet(), found.map { it.name }.toSet())
    }

    // --- voices ------------------------------------------------------------------

    private fun code(id: Int?) = id?.let { KokoroVoices.voice(it)?.code }

    @Test fun eachGetsAVoiceOfTheirOwnKind() {
        val adam = KokoroVoices.all.first { it.code == "am_adam" }.id
        val heart = KokoroVoices.all.first { it.code == "af_heart" }.id
        val fenrir = KokoroVoices.all.first { it.code == "am_fenrir" }.id
        val voiced = CastReading.assignKokoro(cast, narrator = adam, taken = setOf(adam, heart, fenrir))
        val codes = voiced.map { code(it.kokoro) }
        assertEquals(codes.distinct().size, codes.size, "all different")
        assertTrue(codes.none { it in setOf("am_adam", "af_heart", "am_fenrir") }, "not the narrator's, dialogue's or system's")
        assertTrue(code(voiced[0].kokoro)!!.startsWith("am_"), "Hector is male")
        assertTrue(code(voiced[1].kokoro)!!.startsWith("af_"), "Lemon is female")
        assertTrue(codes.none { it == "af_nicole" || it == "am_santa" }, "never the whispering or Santa voice")
    }

    @Test fun someoneOfUnclearGenderKeepsTheDialogueVoice() {
        val tau = CastMember("Tau-indi")
        assertNull(CastReading.assignKokoro(listOf(tau), narrator = 0, taken = setOf(0)).single().kokoro)
        assertNull(CastReading.assignSupertonic(listOf(tau), taken = setOf(0)).single().supertonic)
    }

    @Test fun genderComesFromWhatTheirNarrationSays() {
        val html = chapter(
            "“Eat,” Lemon said.", "“Now,” Lemon said.", "“Go,” Lemon said.",
            // Hector's "his" near her lines would say male; her own "her" wins.
            "Hector watched. Lemon narrowed her eyes. Lemon shook her head. Lemon bit her lip. Lemon pushed her bowl to his side.",
            "Later, Lemon and Hector ate. Then, Lemon left.",
        )
        assertEquals("Female", CastReading.detect(listOf(html)).single().gender)
    }

    @Test fun herHandsOnHisArmStillMakeHerAWoman() {
        // As Aura Overload writes Lemon (5 "his" after her name, 8 "her", 10
        // sentences after hers opening on "She"), scaled down.
        val html = chapter(
            "“Eat,” Lemon said.", "“Now,” Lemon said.", "“Go,” Lemon said.",
            "Lemon took his hand. Lemon grabbed his wrist. Lemon tapped his arm. Lemon shook her head. Lemon bit her lip.",
            "Lemon smiled. She left.", "Lemon sat. She ate.", "Lemon frowned. She sighed.",
            "Lemon waited. She knocked.", "Lemon laughed. She stood.",
            "Later, Lemon and Hector ate. Then, Lemon left.",
        )
        assertEquals("Female", CastReading.detect(listOf(html)).single().gender)
    }

    @Test fun theIsNobody() {
        val html = chapter(*Array(4) { "The big guy stepped back. “Lies.”" }, "Then the rain, and The End, and The End.")
        assertTrue(CastReading.detect(listOf(html)).isEmpty())
    }

    @Test fun aVoiceAlreadyChosenStays() {
        val bella = KokoroVoices.all.first { it.code == "af_bella" }.id
        val chosen = listOf(lemon.copy(kokoro = bella), hector)
        val voiced = CastReading.assignKokoro(chosen, narrator = 0, taken = setOf(0))
        assertEquals(bella, voiced[0].kokoro)
        assertTrue(voiced[1].kokoro != null && voiced[1].kokoro != bella)
    }

    @Test fun supertonicKeepsFemaleAndMaleApart() {
        val voiced = CastReading.assignSupertonic(cast, taken = setOf(0, 5))
        assertTrue(voiced[0].supertonic!! >= 5, "Hector: a male voice")
        assertTrue(voiced[1].supertonic!! < 5, "Lemon: a female voice")
        assertTrue(voiced.none { it.supertonic in setOf(0, 5) })
    }

    @Test fun aCharacterSpeaksInTheirVoice() {
        val bella = KokoroVoices.all.first { it.code == "af_bella" }.id
        val v = NovelVoicing(cast = listOf(lemon.copy(kokoro = bella)))
        assertEquals(bella, ExpressiveReading.characterVoice(v, com.novelscraper.app.data.ReaderPrefs.ENGINE_KOKORO, 0, "Lemon"))
        assertNull(ExpressiveReading.characterVoice(v, com.novelscraper.app.data.ReaderPrefs.ENGINE_KOKORO, 0, "Hector"))
        assertNull(ExpressiveReading.characterVoice(v, com.novelscraper.app.data.ReaderPrefs.ENGINE_KOKORO, bella, "Lemon"),
            "never the narrator's voice")
    }
}
