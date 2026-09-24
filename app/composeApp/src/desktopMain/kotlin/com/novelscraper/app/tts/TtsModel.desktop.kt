package com.novelscraper.app.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * Four threads at most, and half the logical processors below that.
 *
 * Kokoro stops getting faster after about four threads, and every thread past
 * that is spent waiting for the others, busily: on an i7-8750H (6 cores, 12
 * threads) with the full-precision model, 4 threads ran at RTF 0.48 for 1.9
 * CPU-seconds per second of audio, 8 threads at RTF 0.52 for 4.2. On an i7-9700K
 * with the int8 model, 4 threads were RTF 0.86 and 8 threads 0.80.
 */
actual fun defaultTtsThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

// sherpa-onnx's JVM binding (sherpa-onnx-jvm jar + the native-lib jar for this
// OS, which it loads its JNI library from): builder-style config classes.
actual fun buildOfflineTts(spec: TtsModels.Spec, dir: String, threads: Int): TtsModel {
    // On the graphics card when the reader has the pack, it loads, and this
    // voice has been heard to work through it; the processor otherwise. A
    // voice not tried yet is tried in the background while the processor
    // reads, and moves to the card the next time narration starts. One thread
    // is all the card needs: the rest would only wait for it.
    val onCard = GpuVoice.prepare() && when (GpuVoice.probeResult(spec, dir)) {
        true -> true
        false -> false
        null -> { GpuVoice.probeInBackground(spec, dir); false }
    }
    if (onCard) {
        try {
            return buildModel(spec, dir, threads = 1, provider = GpuVoice.provider, modelPath = GpuVoice.modelFile(spec, dir))
                .also { GpuVoice.onCard = true }
        } catch (t: Throwable) {
            GpuVoice.failed(t)
        }
    }
    GpuVoice.onCard = false
    return buildModel(spec, dir, threads, provider = "cpu")
}

internal fun buildModel(
    spec: TtsModels.Spec,
    dir: String,
    threads: Int,
    provider: String,
    modelPath: String = "$dir/${spec.onnx}",
): TtsModel {
    val model = OfflineTtsModelConfig.Builder().apply {
        if (spec.kind == "vits") {
            setVits(
                OfflineTtsVitsModelConfig.Builder()
                    .setModel(modelPath)
                    .setTokens("$dir/tokens.txt")
                    .setDataDir("$dir/espeak-ng-data")
                    .build(),
            )
        } else {
            setKokoro(
                OfflineTtsKokoroModelConfig.Builder()
                    .setModel(modelPath)
                    .setVoices("$dir/voices.bin")
                    .setTokens("$dir/tokens.txt")
                    .setDataDir("$dir/espeak-ng-data")
                    .setLexicon("$dir/lexicon-us-en.txt")
                    .setLang("en")
                    .build(),
            )
        }
        setNumThreads(threads)
        setProvider(provider)
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
