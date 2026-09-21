package com.novelscraper.app.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * Cap synthesis threads at 4. On big.LITTLE phones an ONNX op finishes only
 * when its slowest thread does, so spilling onto slow cores makes generation
 * *slower*. 4 keeps work on the fast cores.
 */
actual fun defaultTtsThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

// sherpa-onnx's Android binding (the bundled .aar): mutable config classes.
actual fun buildOfflineTts(spec: TtsModels.Spec, dir: String, threads: Int): TtsModel {
    val modelConfig = OfflineTtsModelConfig().apply {
        if (spec.kind == "vits") {
            this.vits = OfflineTtsVitsModelConfig().apply {
                model = "$dir/${spec.onnx}"
                tokens = "$dir/tokens.txt"
                dataDir = "$dir/espeak-ng-data"
            }
        } else {
            this.kokoro = OfflineTtsKokoroModelConfig().apply {
                model = "$dir/${spec.onnx}"
                voices = "$dir/voices.bin"
                tokens = "$dir/tokens.txt"
                dataDir = "$dir/espeak-ng-data"
                lexicon = "$dir/lexicon-us-en.txt"
                lang = "en"
            }
        }
        this.numThreads = threads
        provider = "cpu"
        debug = false
    }
    val tts = OfflineTts(null, OfflineTtsConfig().apply { this.model = modelConfig })
    return object : TtsModel {
        override val sampleRate = tts.sampleRate()
        override val numSpeakers = tts.numSpeakers()
        override fun generate(text: String, speaker: Int, speed: Float) = tts.generate(text, speaker, speed).samples
        override fun release() = tts.release()
    }
}
