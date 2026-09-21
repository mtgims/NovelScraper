package com.novelscraper.app.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/** Desktop cores are all fast: use most of them, leaving two for the UI and audio.
 *  (Measured on an 8-core i7-9700K with Kokoro int8: 4 threads RTF 0.86, 6 threads
 *  0.81, 8 threads 0.80, so more than that buys nothing.) */
actual fun defaultTtsThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 8)

// sherpa-onnx's JVM binding (sherpa-onnx-jvm jar + the native-lib jar for this
// OS, which it loads its JNI library from): builder-style config classes.
actual fun buildOfflineTts(spec: TtsModels.Spec, dir: String, threads: Int): TtsModel {
    val model = OfflineTtsModelConfig.Builder().apply {
        if (spec.kind == "vits") {
            setVits(
                OfflineTtsVitsModelConfig.Builder()
                    .setModel("$dir/${spec.onnx}")
                    .setTokens("$dir/tokens.txt")
                    .setDataDir("$dir/espeak-ng-data")
                    .build(),
            )
        } else {
            setKokoro(
                OfflineTtsKokoroModelConfig.Builder()
                    .setModel("$dir/${spec.onnx}")
                    .setVoices("$dir/voices.bin")
                    .setTokens("$dir/tokens.txt")
                    .setDataDir("$dir/espeak-ng-data")
                    .setLexicon("$dir/lexicon-us-en.txt")
                    .setLang("en")
                    .build(),
            )
        }
        setNumThreads(threads)
        setProvider("cpu")
        setDebug(false)
    }.build()
    val tts = OfflineTts(OfflineTtsConfig.Builder().setModel(model).build())
    return object : TtsModel {
        override val sampleRate = tts.sampleRate
        override val numSpeakers = tts.numSpeakers
        override fun generate(text: String, speaker: Int, speed: Float): FloatArray =
            tts.generate(text, speaker, speed).samples
        override fun release() = tts.release()
    }
}
