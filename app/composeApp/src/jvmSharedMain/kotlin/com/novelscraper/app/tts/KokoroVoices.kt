package com.novelscraper.app.tts

/**
 * The 53 voices of kokoro-int8-multi-lang-v1_0, in the exact order the model's
 * voices.bin was built (sherpa-onnx scripts/kokoro/v1.0/generate_voices_bin.py),
 * so the list index == the speaker id passed to OfflineTts.
 *
 * Naming: first letter = language/accent (a=American English, b=British English,
 * e=Spanish, f=French, h=Hindi, i=Italian, j=Japanese, p=Portuguese, z=Mandarin),
 * second = gender (f/m), the rest = name.
 */
object KokoroVoices {

    private val CODES: List<String> = listOf(
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore",
        "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
        "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael",
        "am_onyx", "am_puck", "am_santa",
        "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
        "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
        "ef_dora", "em_alex",
        "ff_siwis",
        "hf_alpha", "hf_beta", "hm_omega", "hm_psi",
        "if_sara", "im_nicola",
        "jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo",
        "pf_dora", "pm_alex", "pm_santa",
        "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi",
        "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
    )

    // Language order for the picker — English first (what most readers want here).
    private val LANG_ORDER = listOf(
        "American English", "British English", "Spanish", "French", "Italian",
        "Hindi", "Japanese", "Portuguese", "Chinese (Mandarin)",
    )

    data class Voice(
        val id: Int,
        val code: String,
        val language: String,
        val gender: String,
        val name: String,
    ) {
        /** e.g. "Michael · Male". */
        val label: String get() = if (gender.isEmpty()) name else "$name · $gender"
    }

    val all: List<Voice> = CODES.mapIndexed { id, code ->
        val language = when (code.firstOrNull()) {
            'a' -> "American English"
            'b' -> "British English"
            'e' -> "Spanish"
            'f' -> "French"
            'h' -> "Hindi"
            'i' -> "Italian"
            'j' -> "Japanese"
            'p' -> "Portuguese"
            'z' -> "Chinese (Mandarin)"
            else -> "Other"
        }
        val gender = when (code.getOrNull(1)) {
            'f' -> "Female"
            'm' -> "Male"
            else -> ""
        }
        val name = code.substringAfter('_').replaceFirstChar { it.uppercase() }
        Voice(id, code, language, gender, name)
    }

    /** Voices grouped by language in [LANG_ORDER], each in ascending id order. */
    val groups: List<Pair<String, List<Voice>>> =
        (LANG_ORDER + "Other").mapNotNull { lang ->
            all.filter { it.language == lang }.takeIf { it.isNotEmpty() }?.let { lang to it }
        }

    fun voice(id: Int): Voice? = all.getOrNull(id)

    /** Short description for a chip/row: "Michael · American English". */
    fun describe(id: Int): String =
        voice(id)?.let { "${it.name} · ${it.language}" } ?: "Voice #$id"
}
