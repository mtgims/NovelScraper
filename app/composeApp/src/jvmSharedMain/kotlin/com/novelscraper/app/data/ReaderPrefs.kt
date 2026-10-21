package com.novelscraper.app.data

import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.hasSystemTts
import com.novelscraper.app.platform.settingsStore
import com.novelscraper.app.tts.NarrationText
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
    const val ENGINE_SUPERTONIC = "supertonic"

    /** The on-device model [engine] narrates with. */
    fun modelFor(engine: String): String = when (engine) {
        ENGINE_KOKORO -> com.novelscraper.app.tts.TtsModels.KOKORO
        ENGINE_SUPERTONIC -> com.novelscraper.app.tts.TtsModels.SUPERTONIC
        else -> piperVoice.value
    }

    /** The speaker [engine] narrates with: Piper voices have one each. */
    fun speakerFor(engine: String): Int = when (engine) {
        ENGINE_KOKORO -> kokoroSpeaker.value
        ENGINE_SUPERTONIC -> supertonicSpeaker.value
        else -> 0
    }

    private lateinit var prefs: KeyValueStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
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

    // Supertonic speaker id (0-4 the female voices, 5-9 the male ones).
    private val _supertonicSpeaker = MutableStateFlow(0)
    val supertonicSpeaker: StateFlow<Int> = _supertonicSpeaker.asStateFlow()

    // Selected Piper voice model id (e.g. "en_US-amy-medium").
    private val _piperVoice = MutableStateFlow("en_US-amy-medium")
    val piperVoice: StateFlow<String> = _piperVoice.asStateFlow()

    // Roll narration into the next chapter automatically when one finishes.
    private val _ttsAutoNext = MutableStateFlow(true)
    val ttsAutoNext: StateFlow<Boolean> = _ttsAutoNext.asStateFlow()

    // Pass over site plugs, patron links and translator notes while narrating
    // (see NarrationText), plus the reader's own lines to skip.
    private val _ttsSkipJunk = MutableStateFlow(true)
    val ttsSkipJunk: StateFlow<Boolean> = _ttsSkipJunk.asStateFlow()

    // Expressive reading (a desktop beta): dialogue in a second voice, pauses
    // and pacing that follow the text. The dialogue voices are -1 for "pick one
    // to go with the narrator".
    private val _ttsExpressive = MutableStateFlow(false)
    val ttsExpressive: StateFlow<Boolean> = _ttsExpressive.asStateFlow()

    private val _kokoroDialogueSpeaker = MutableStateFlow(-1)
    val kokoroDialogueSpeaker: StateFlow<Int> = _kokoroDialogueSpeaker.asStateFlow()

    private val _supertonicDialogueSpeaker = MutableStateFlow(-1)
    val supertonicDialogueSpeaker: StateFlow<Int> = _supertonicDialogueSpeaker.asStateFlow()

    private val _ttsJunkPatterns = MutableStateFlow<List<String>>(emptyList())
    val ttsJunkPatterns: StateFlow<List<String>> = _ttsJunkPatterns.asStateFlow()

    // How words should sound ("Xianxia" -> "shyen-shya").
    private val _ttsDictionary = MutableStateFlow<List<NarrationText.Term>>(emptyList())
    val ttsDictionary: StateFlow<List<NarrationText.Term>> = _ttsDictionary.asStateFlow()

    fun init() {
        prefs = settingsStore("reader")
        loadRecentScroll()
        _fontScale.value = prefs.getFloat("font_scale", 1.0f)
        _ttsRate.value = prefs.getFloat("tts_rate", 1.0f)
        _ttsVoice.value = prefs.getString("tts_voice", "") ?: ""
        _ttsEngine.value = normalizeEngine(prefs.getString("tts_engine", ENGINE_DEVICE) ?: ENGINE_DEVICE)
        _kokoroSpeaker.value = prefs.getInt("kokoro_speaker", 0)
        _supertonicSpeaker.value = prefs.getInt("supertonic_speaker", 0)
        _piperVoice.value = prefs.getString("piper_voice", "en_US-amy-medium") ?: "en_US-amy-medium"
        _ttsAutoNext.value = prefs.getBoolean("tts_auto_next", true)
        _ttsSkipJunk.value = prefs.getBoolean("tts_skip_junk", true)
        _ttsExpressive.value = prefs.getBoolean("tts_expressive", false)
        _kokoroDialogueSpeaker.value = prefs.getInt("kokoro_dialogue_speaker", -1)
        _supertonicDialogueSpeaker.value = prefs.getInt("supertonic_dialogue_speaker", -1)
        _ttsJunkPatterns.value = prefs.getString("tts_junk_patterns", null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            .orEmpty()
        _ttsDictionary.value = prefs.getString("tts_dictionary", null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(NarrationText.Term.serializer()), it) }.getOrNull() }
            .orEmpty()
    }

    fun setTtsSkipJunk(on: Boolean) {
        _ttsSkipJunk.value = on
        prefs.putBoolean("tts_skip_junk", on)
    }

    fun setTtsExpressive(on: Boolean) {
        _ttsExpressive.value = on
        prefs.putBoolean("tts_expressive", on)
    }

    /** -1 picks one to go with the narrator. */
    fun setKokoroDialogueSpeaker(id: Int) {
        val v = id.coerceAtLeast(-1)
        _kokoroDialogueSpeaker.value = v
        prefs.putInt("kokoro_dialogue_speaker", v)
    }

    fun setSupertonicDialogueSpeaker(id: Int) {
        val v = id.coerceAtLeast(-1)
        _supertonicDialogueSpeaker.value = v
        prefs.putInt("supertonic_dialogue_speaker", v)
    }

    /** The reader's own lines to pass over (matched anywhere in a sentence). */
    fun setTtsJunkPatterns(patterns: List<String>) {
        val clean = patterns.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        _ttsJunkPatterns.value = clean
        prefs.putString("tts_junk_patterns", json.encodeToString(ListSerializer(String.serializer()), clean))
    }

    fun setTtsDictionary(terms: List<NarrationText.Term>) {
        val clean = terms.filter { it.from.isNotBlank() }.distinctBy { it.from.lowercase() }
        _ttsDictionary.value = clean
        prefs.putString("tts_dictionary", json.encodeToString(ListSerializer(NarrationText.Term.serializer()), clean))
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
        val e = normalizeEngine(engine)
        _ttsEngine.value = e
        prefs.putString("tts_engine", e)
    }

    /** A known engine id; without a platform speech engine (desktop), "device"
     *  (the default) means Piper, the fast downloadable voice. */
    private fun normalizeEngine(engine: String): String = when (engine) {
        ENGINE_KOKORO -> ENGINE_KOKORO
        ENGINE_PIPER -> ENGINE_PIPER
        ENGINE_SUPERTONIC -> ENGINE_SUPERTONIC
        else -> if (hasSystemTts) ENGINE_DEVICE else ENGINE_PIPER
    }

    fun setKokoroSpeaker(id: Int) {
        val v = id.coerceAtLeast(0)
        _kokoroSpeaker.value = v
        prefs.putInt("kokoro_speaker", v)
    }

    fun setSupertonicSpeaker(id: Int) {
        val v = id.coerceAtLeast(0)
        _supertonicSpeaker.value = v
        prefs.putInt("supertonic_speaker", v)
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

    /** How many chapters keep their place (see [setScroll]). */
    const val MAX_SCROLL_KEYS = 500

    /** In-chapter scroll, per novel ([ReaderViewModel.scrollKey]) and chapter. */
    fun getScroll(novelKey: String, position: Int): Float =
        prefs.getFloat("scroll_${novelKey}_$position", 0f)

    /**
     * Where reading stopped in a chapter. Every chapter ever opened kept its
     * key for good, so the settings file only grew, and it is rewritten whole.
     * Only the [MAX_SCROLL_KEYS] most recently read chapters keep theirs now;
     * an older one opens at its top.
     */
    fun setScroll(novelKey: String, position: Int, fraction: Float) {
        val key = "scroll_${novelKey}_$position"
        prefs.putFloat(key, fraction.coerceIn(0f, 1f))
        synchronized(recentScroll) {
            recentScroll.remove(key)
            recentScroll.add(key)
            trimScroll()
            prefs.putString(RECENT_SCROLL, json.encodeToString(ListSerializer(String.serializer()), recentScroll.toList()))
        }
    }

    /** The chapters that keep their place, least recently read first. */
    private val recentScroll = LinkedHashSet<String>()

    private fun trimScroll() {
        while (recentScroll.size > MAX_SCROLL_KEYS) {
            val oldest = recentScroll.first()
            recentScroll.remove(oldest)
            prefs.remove(oldest)
        }
    }

    /** Read the list of kept places; on first use, start it from whatever is stored. */
    private fun loadRecentScroll() = synchronized(recentScroll) {
        recentScroll.clear()
        val listed = prefs.getString(RECENT_SCROLL, null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
        recentScroll.addAll(listed ?: prefs.keys().filter { it.startsWith("scroll_") }.sorted())
        trimScroll()
    }

    private const val RECENT_SCROLL = "recent_scroll_keys"
}
