package com.novelscraper.app.tts

/**
 * The 103 Kokoro voices of kokoro-int8-multi-lang-v1_1, in the exact order the
 * model's voices.bin was built (so the list index == the speaker id passed to
 * OfflineTts). Order taken from sherpa-onnx scripts/kokoro/v1.1-zh
 * (generate_voices_bin.py + run.sh) cross-checked against the HF file list:
 * af_maple, af_sol, bf_vale, then the zf_* / zm_* files that actually exist.
 *
 * Naming convention: first letter = accent (a=American, b=British, z=Mandarin),
 * second = gender (f/m), rest = name or dataset speaker number.
 */
object KokoroVoices {

    private val CODES: List<String> = listOf(
        "af_maple", "af_sol", "bf_vale",
        "zf_001", "zf_002", "zf_003", "zf_004", "zf_005", "zf_006", "zf_007", "zf_008",
        "zf_017", "zf_018", "zf_019", "zf_021", "zf_022", "zf_023", "zf_024", "zf_026",
        "zf_027", "zf_028", "zf_032", "zf_036", "zf_038", "zf_039", "zf_040", "zf_042",
        "zf_043", "zf_044", "zf_046", "zf_047", "zf_048", "zf_049", "zf_051", "zf_059",
        "zf_060", "zf_067", "zf_070", "zf_071", "zf_072", "zf_073", "zf_074", "zf_075",
        "zf_076", "zf_077", "zf_078", "zf_079", "zf_083", "zf_084", "zf_085", "zf_086",
        "zf_087", "zf_088", "zf_090", "zf_092", "zf_093", "zf_094", "zf_099",
        "zm_009", "zm_010", "zm_011", "zm_012", "zm_013", "zm_014", "zm_015", "zm_016",
        "zm_020", "zm_025", "zm_029", "zm_030", "zm_031", "zm_033", "zm_034", "zm_035",
        "zm_037", "zm_041", "zm_045", "zm_050", "zm_052", "zm_053", "zm_054", "zm_055",
        "zm_056", "zm_057", "zm_058", "zm_061", "zm_062", "zm_063", "zm_064", "zm_065",
        "zm_066", "zm_068", "zm_069", "zm_080", "zm_081", "zm_082", "zm_089", "zm_091",
        "zm_095", "zm_096", "zm_097", "zm_098", "zm_100",
    )

    data class Voice(
        val id: Int,
        val code: String,
        val nationality: String,
        val gender: String,
        val name: String,
    )

    val all: List<Voice> = CODES.mapIndexed { id, code ->
        val nationality = when (code.firstOrNull()) {
            'a' -> "American English"
            'b' -> "British English"
            'z' -> "Chinese (Mandarin)"
            else -> "Other"
        }
        val gender = when (code.getOrNull(1)) {
            'f' -> "Female"
            'm' -> "Male"
            else -> ""
        }
        val raw = code.substringAfter('_')
        val name = if (raw.all { it.isDigit() }) raw else raw.replaceFirstChar { it.uppercase() }
        Voice(id, code, nationality, gender, name)
    }

    /**
     * Voices split into sensible sections for the picker, English first. Chinese is
     * split by gender so each section's numbers run monotonically (otherwise the
     * combined list jumps 099 -> 009 at the female/male boundary and looks unsorted).
     * Each pair is (section label, voices in ascending id order).
     */
    val groups: List<Pair<String, List<Voice>>> = buildList {
        fun section(label: String, predicate: (Voice) -> Boolean) {
            all.filter(predicate).takeIf { it.isNotEmpty() }?.let { add(label to it) }
        }
        section("American English") { it.nationality == "American English" }
        section("British English") { it.nationality == "British English" }
        section("Chinese · Female") { it.nationality == "Chinese (Mandarin)" && it.gender == "Female" }
        section("Chinese · Male") { it.nationality == "Chinese (Mandarin)" && it.gender == "Male" }
        section("Other") { it.nationality == "Other" }
    }

    fun voice(id: Int): Voice? = all.getOrNull(id)

    /** Short description for a chip/row: "Maple · American English". */
    fun describe(id: Int): String =
        voice(id)?.let { "${it.name} · ${it.nationality}" } ?: "Voice #$id"
}
