package com.novelscraper.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reader preferences + per-chapter scroll positions, backed by SharedPreferences.
 * Font scale is exposed as a StateFlow so the reader restyles live. Initialised
 * from [com.novelscraper.app.App].
 */
object ReaderPrefs {
    private const val MIN = 0.8f
    private const val MAX = 1.8f
    private const val STEP = 0.1f

    const val ENGINE_DEVICE = "device"
    const val ENGINE_KOKORO = "kokoro"

    private lateinit var prefs: SharedPreferences
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

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("reader", Context.MODE_PRIVATE)
        _fontScale.value = prefs.getFloat("font_scale", 1.0f)
        _ttsRate.value = prefs.getFloat("tts_rate", 1.0f)
        _ttsVoice.value = prefs.getString("tts_voice", "") ?: ""
        _ttsEngine.value = prefs.getString("tts_engine", ENGINE_DEVICE) ?: ENGINE_DEVICE
        _kokoroSpeaker.value = prefs.getInt("kokoro_speaker", 0)
    }

    fun setTtsEngine(engine: String) {
        val e = if (engine == ENGINE_KOKORO) ENGINE_KOKORO else ENGINE_DEVICE
        _ttsEngine.value = e
        prefs.edit().putString("tts_engine", e).apply()
    }

    fun setKokoroSpeaker(id: Int) {
        val v = id.coerceAtLeast(0)
        _kokoroSpeaker.value = v
        prefs.edit().putInt("kokoro_speaker", v).apply()
    }

    fun setTtsRate(v: Float) {
        val clamped = v.coerceIn(0.5f, 2.5f)
        _ttsRate.value = clamped
        prefs.edit().putFloat("tts_rate", clamped).apply()
    }

    fun setTtsVoice(name: String) {
        _ttsVoice.value = name
        prefs.edit().putString("tts_voice", name).apply()
    }

    fun increaseFont() = setFontScale(_fontScale.value + STEP)
    fun decreaseFont() = setFontScale(_fontScale.value - STEP)

    private fun setFontScale(v: Float) {
        val clamped = v.coerceIn(MIN, MAX)
        _fontScale.value = clamped
        prefs.edit().putFloat("font_scale", clamped).apply()
    }

    fun getScroll(bookId: Int, position: Int): Float =
        prefs.getFloat("scroll_${bookId}_$position", 0f)

    fun setScroll(bookId: Int, position: Int, fraction: Float) {
        prefs.edit().putFloat("scroll_${bookId}_$position", fraction.coerceIn(0f, 1f)).apply()
    }
}
