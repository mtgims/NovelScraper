package com.novelscraper.app.tts

import com.novelscraper.app.platform.appFilesDir
import java.io.File
import com.novelscraper.app.platform.isDesktop

/**
 * The downloadable on-device voice models, addressed by a "model id":
 *  - "kokoro": Kokoro 82M multi-lang (53 voices; most natural, ~1x real time), and
 *  - a Piper/VITS voice (each id like "en_US-amy-medium"; single speaker, several×
 *    real time; less expressive but far faster), and
 *  - "supertonic": Supertonic 3 (ten voices; nearly Kokoro's naturalness at
 *    several times its speed).
 * Models live in the app's private files dir, one folder each.
 */
object TtsModels {
    const val KOKORO = "kokoro"

    /**
     * Supertonic 3 (Supertone, 99M, OpenRAIL-M), int8 as sherpa-onnx packages it.
     * Close to Kokoro's naturalness at a fraction of its cost: on an i7-8750H,
     * RTF 0.21 for 0.46 CPU-seconds per second of audio on two threads, where
     * Kokoro takes 0.48 for 1.9, so it keeps ahead of the voice on a phone too.
     */
    const val SUPERTONIC = "supertonic"

    /** Supertonic's ten voices in the order of its speaker ids: measured by
     *  pitch, 0-4 are the higher (158-191 Hz) and 5-9 the lower (87-129 Hz). */
    val SUPERTONIC_VOICES: List<String> = (1..5).map { "Female $it" } + (1..5).map { "Male $it" }

    private const val BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    /** A named Piper voice (id == the sherpa model basename). */
    data class PiperVoice(val id: String, val name: String, val accent: String, val gender: String)

    val PIPER_VOICES: List<PiperVoice> = listOf(
        PiperVoice("en_US-amy-medium", "Amy", "American English", "Female"),
        PiperVoice("en_US-ryan-medium", "Ryan", "American English", "Male"),
        PiperVoice("en_US-lessac-medium", "Lessac", "American English", "Female"),
        PiperVoice("en_US-joe-medium", "Joe", "American English", "Male"),
        PiperVoice("en_GB-alan-medium", "Alan", "British English", "Male"),
        PiperVoice("en_GB-cori-medium", "Cori", "British English", "Female"),
        PiperVoice("en_GB-alba-medium", "Alba", "British English", "Female"),
        PiperVoice("en_GB-northern_english_male-medium", "Northern", "British English", "Male"),
    )

    data class Spec(
        val dir: String,
        val url: String,
        val kind: String,          // "kokoro" | "vits" | "supertonic"
        val onnx: String,
        val required: List<String>,
    )

    private val SPECS: Map<String, Spec> = buildMap {
        // Kokoro comes quantised to eight bits and at full precision. The small
        // one is a quarter of the size and sounds it: the voice is recognisably
        // the same person with gravel poured over it, which is no good for
        // something meant to be listened to for hours. A computer has the room
        // and the processor for the full one; a phone keeps the small one.
        put(
            KOKORO,
            if (isDesktop) Spec(
                dir = "kokoro-multi-lang-v1_0",
                url = "$BASE/kokoro-multi-lang-v1_0.tar.bz2",
                kind = "kokoro",
                onnx = "model.onnx",
                required = listOf("model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt"),
            ) else Spec(
                dir = "kokoro-int8-multi-lang-v1_0",
                url = "$BASE/kokoro-int8-multi-lang-v1_0.tar.bz2",
                kind = "kokoro",
                onnx = "model.int8.onnx",
                required = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt"),
            ),
        )
        put(
            SUPERTONIC,
            Spec(
                dir = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
                url = "$BASE/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
                kind = "supertonic",
                onnx = "vector_estimator.int8.onnx",
                required = listOf(
                    "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
                    "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin",
                ),
            ),
        )
        for (v in PIPER_VOICES) {
            put(
                v.id,
                Spec(
                    dir = "vits-piper-${v.id}",
                    url = "$BASE/vits-piper-${v.id}.tar.bz2",
                    kind = "vits",
                    onnx = "${v.id}.onnx",
                    required = listOf("${v.id}.onnx", "tokens.txt"),
                ),
            )
        }
    }

    fun spec(modelId: String): Spec = SPECS[modelId] ?: SPECS.getValue(KOKORO)

    fun modelDir(modelId: String): File = File(appFilesDir(), spec(modelId).dir)

    fun downloadUrl(modelId: String): String = spec(modelId).url

    /** True once every file the model needs is present on disk. */
    fun isModelReady(modelId: String): Boolean {
        val s = spec(modelId)
        val d = File(appFilesDir(), s.dir)
        // Kokoro and Piper spell words out through espeak-ng's data; Supertonic
        // reads the characters themselves and has none.
        val espeak = s.kind == "supertonic" || File(d, "espeak-ng-data").isDirectory
        return s.required.all { File(d, it).exists() } && espeak
    }

    /** Delete previously-downloaded models no longer offered (e.g. an old Kokoro
     *  v1.1); keeps Kokoro and every listed Piper voice the user may have fetched. */
    fun cleanupOtherModels() {
        val keep = SPECS.values.map { it.dir }.toSet()
        appFilesDir().listFiles()
            ?.filter {
                it.isDirectory && it.name !in keep &&
                    (it.name.startsWith("kokoro-") || it.name.startsWith("vits-") || it.name.startsWith("sherpa-onnx-supertonic-"))
            }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }
}
