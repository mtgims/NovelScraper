package com.novelscraper.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Saves where reading stopped in a chapter, once the scrolling has: [save] used
 * to run on every frame of a scroll, sixty writes a second for one flick. Now
 * it runs [quietMs] after the last move, and at once on [flush] (the chapter
 * closing), so the last place is never lost.
 */
class ScrollSaver(
    private val scope: CoroutineScope,
    private val quietMs: Long = 500,
    private val save: (Float) -> Unit,
) {
    private var waiting: Job? = null
    private var unsaved: Float? = null

    @Synchronized
    fun update(fraction: Float) {
        unsaved = fraction
        waiting?.cancel()
        waiting = scope.launch {
            delay(quietMs)
            flush()
        }
    }

    /** Save now, if anything is waiting to be. */
    @Synchronized
    fun flush() {
        waiting?.cancel()
        waiting = null
        unsaved?.let(save)
        unsaved = null
    }
}
