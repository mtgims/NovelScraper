package com.novelscraper.app.tts

/** A loaded sherpa-onnx voice model. */
interface TtsModel {
    val sampleRate: Int
    val numSpeakers: Int

    /** Mono float PCM at [sampleRate]. */
    fun generate(text: String, speaker: Int, speed: Float): FloatArray

    fun release()
}

/** Synthesis threads for this device. */
expect fun defaultTtsThreads(): Int

/** Build the native model for [spec] from the files in [dir]. Throws if the
 *  native library or the model can't be loaded. */
expect fun buildOfflineTts(spec: TtsModels.Spec, dir: String, threads: Int): TtsModel
