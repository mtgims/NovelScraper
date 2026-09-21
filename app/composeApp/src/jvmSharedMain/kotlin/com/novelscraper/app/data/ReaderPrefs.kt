package com.novelscraper.app.data

import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.settingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reader preferences + per-chapter scroll positions, in the "reader" settings store.
 * Font scale is exposed as a StateFlow so the reader restyles live. Initialised
 * from [com.novelscraper.app.App].
 */
object ReaderPrefs {
    private const val MIN = 0.8f
    private const val MAX = 1.8f
    private const val STEP = 0.1f

    const val ENGINE_DEVICE = "device"
    const val ENGINE_KOKORO = "kokoro"
    const val ENGINE_PIPER = "piper"

    private lateinit var prefs: KeyValueStore
    private val _fontScale = MutableStateFlow(1.0f)
    val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

    // Narration speech rate (applied by TtsService via TextToSpeech.setSpeechRate).
    private val _ttsRate = MutableStateFlow(1.0f)
    val ttsRate: StateFlow<Float> = _ttsRate.asStateFlow()

    // Selected device TTS voice name ("" = engine default).
    private val _ttsVoice = MutableStateFlow("")
    val ttsVoice: StateFlow<String> = _ttsVoice.asStateFlow()

    // Narration engine: "device" (android TextToSpeech) or "kokoro" (on-device
    // sherpa-onnx neural TTS, downloaded on first use).
    private val _ttsEngine = MutableStateFlow(ENGINE_DEVICE)
    val ttsEngine: StateFlow<String> = _ttsEngine.asStateFlow()

    // Kokoro speaker id (index into the model's voice table).
    private val _kokoroSpeaker = MutableStateFlow(0)
    val kokoroSpeaker: StateFlow<Int> = _kokoroSpeaker.asStateFlow()

    // Selected Piper voice model id (e.g. "en_US-amy-medium").
    private val _piperVoice = MutableStateFlow("en_US-amy-medium")
    val piperVoice: StateFlow<String> = _piperVoice.asStateFlow()

    // Roll narration into the next chapter automatically when one finishes.
    private val _ttsAutoNext = MutableStateFlow(true)
    val ttsAutoNext: StateFlow<Boolean> = _ttsAutoNext.asStateFlow()

    fun init() {
        prefs = settingsStore("reader")
        _fontScale.value = prefs.getFloat("font_scale", 1.0f)
        _ttsRate.value = prefs.getFloat("tts_rate", 1.0f)
        _ttsVoice.value = prefs.getString("tts_voice", "") ?: ""
        _ttsEngine.value = prefs.getString("tts_engine", ENGINE_DEVICE) ?: ENGINE_DEVICE
        _kokoroSpeaker.value = prefs.getInt("kokoro_speaker", 0)
        _piperVoice.value = prefs.getString("piper_voice", "en_US-amy-medium") ?: "en_US-amy-medium"
        _ttsAutoNext.value = prefs.getBoolean("tts_auto_next", true)
    }

    fun setPiperVoice(id: String) {
        _piperVoice.value = id
        prefs.putString("piper_voice", id)
    }

    fun setTtsAutoNext(on: Boolean) {
        _ttsAutoNext.value = on
        prefs.putBoolean("tts_auto_next", on)
    }

    fun setTtsEngine(engine: String) {
        val e = when (engine) {
            ENGINE_KOKORO -> ENGINE_KOKORO
            ENGINE_PIPER -> ENGINE_PIPER
            else -> ENGINE_DEVICE
        }
        _ttsEngine.value = e
        prefs.putString("tts_engine", e)
    }

    fun setKokoroSpeaker(id: Int) {
        val v = id.coerceAtLeast(0)
        _kokoroSpeaker.value = v
        prefs.putInt("kokoro_speaker", v)
    }

    fun setTtsRate(v: Float) {
        val clamped = v.coerceIn(0.5f, 2.5f)
        _ttsRate.value = clamped
        prefs.putFloat("tts_rate", clamped)
    }

    fun setTtsVoice(name: String) {
        _ttsVoice.value = name
        prefs.putString("tts_voice", name)
    }

    fun increaseFont() = setFontScale(_fontScale.value + STEP)
    fun decreaseFont() = setFontScale(_fontScale.value - STEP)

    private fun setFontScale(v: Float) {
        val clamped = v.coerceIn(MIN, MAX)
        _fontScale.value = clamped
        prefs.putFloat("font_scale", clamped)
    }

    fun getScroll(bookId: Int, position: Int): Float =
        prefs.getFloat("scroll_${bookId}_$position", 0f)

    fun setScroll(bookId: Int, position: Int, fraction: Float) {
        prefs.putFloat("scroll_${bookId}_$position", fraction.coerceIn(0f, 1f))
    }
}
