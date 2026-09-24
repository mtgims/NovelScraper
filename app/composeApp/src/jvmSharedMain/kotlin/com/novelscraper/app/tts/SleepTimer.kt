package com.novelscraper.app.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stops narration after a while, for listening in bed: either after a number of
 * minutes, or at the end of the chapter being read. Shared by both apps; the
 * narrator asks [stopAtChapterEnd] before rolling into the next chapter, and the
 * minute timer pauses playback itself.
 */
object SleepTimer {

    sealed interface Mode {
        data object Off : Mode
        /** Pause when the clock runs out. */
        data class Minutes(val total: Int) : Mode
        /** Pause when the chapter being read finishes. */
        data object ChapterEnd : Mode
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _mode = MutableStateFlow<Mode>(Mode.Off)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    /** Seconds left on a minutes timer (0 when it isn't running). */
    private val _remainingSec = MutableStateFlow(0)
    val remainingSec: StateFlow<Int> = _remainingSec.asStateFlow()

    internal val stopAtChapterEnd: Boolean get() = _mode.value == Mode.ChapterEnd

    /** Stop narrating in [minutes] minutes. */
    fun setMinutes(minutes: Int) {
        cancelJob()
        if (minutes <= 0) { clear(); return }
        _mode.value = Mode.Minutes(minutes)
        _remainingSec.value = minutes * 60
        job = scope.launch {
            while (_remainingSec.value > 0) {
                delay(1_000)
                _remainingSec.value -= 1
            }
            TtsController.pause()
            clear()
        }
    }

    /** Stop narrating when this chapter ends. */
    fun setChapterEnd() {
        cancelJob()
        _mode.value = Mode.ChapterEnd
        _remainingSec.value = 0
    }

    fun cancel() { cancelJob(); clear() }

    /** The narrator finished a chapter (and so stopped, if that was the mode). */
    internal fun chapterEnded() {
        if (stopAtChapterEnd) clear()
    }

    /** Narration stopped for any other reason: the timer goes with it. */
    internal fun narrationStopped() {
        if (_mode.value is Mode.Minutes) return   // keep counting; the user may resume
        clear()
    }

    private fun cancelJob() { job?.cancel(); job = null }

    private fun clear() {
        _mode.value = Mode.Off
        _remainingSec.value = 0
    }
}
