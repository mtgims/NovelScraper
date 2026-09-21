package com.novelscraper.app.tts

import com.novelscraper.app.platform.appFilesDir
import java.io.File

/**
 * The downloadable on-device voice models, addressed by a "model id":
 *  - "kokoro": Kokoro 82M multi-lang (53 voices; most natural, ~1x real time), and
 *  - a Piper/VITS voice (each id like "en_US-amy-medium"; single speaker, several×
 *    real time; less expressive but far faster).
 * Models live in the app's private files dir, one folder each.
 */
object TtsModels {
    const val KOKORO = "kokoro"

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

    fun defaultPiperVoice(): String = PIPER_VOICES.first().id

    data class Spec(
        val dir: String,
        val url: String,
        val kind: String,          // "kokoro" | "vits"
        val onnx: String,
        val required: List<String>,
    )

    private val SPECS: Map<String, Spec> = buildMap {
        put(
            KOKORO,
            Spec(
                dir = "kokoro-int8-multi-lang-v1_0",
                url = "$BASE/kokoro-int8-multi-lang-v1_0.tar.bz2",
                kind = "kokoro",
                onnx = "model.int8.onnx",
                required = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt"),
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
        return s.required.all { File(d, it).exists() } && File(d, "espeak-ng-data").isDirectory
    }

    /** Delete previously-downloaded models no longer offered (e.g. an old Kokoro
     *  v1.1); keeps Kokoro and every listed Piper voice the user may have fetched. */
    fun cleanupOtherModels() {
        val keep = SPECS.values.map { it.dir }.toSet()
        appFilesDir().listFiles()
            ?.filter { it.isDirectory && (it.name.startsWith("kokoro-") || it.name.startsWith("vits-")) && it.name !in keep }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }
}
