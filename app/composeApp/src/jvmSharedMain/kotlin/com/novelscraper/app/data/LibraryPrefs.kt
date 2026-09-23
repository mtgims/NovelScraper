package com.novelscraper.app.data

import com.novelscraper.app.platform.isDesktop
import com.novelscraper.app.platform.settingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the library looks, kept between runs.
 *
 * The one thing a shelf of covers needs and a phone never offered: how big the
 * covers are. A phone has one sensible answer and a window has as many as there
 * are window sizes, so it is the reader's to set.
 */
object LibraryPrefs {

    private const val KEY_COVER = "cover-width"
    private const val KEY_RAIL = "rail-collapsed"

    /** The width a cover is laid out at. The grid fits as many as will go. */
    private val _coverWidth = MutableStateFlow(if (isDesktop) 168 else 150)
    val coverWidth: StateFlow<Int> = _coverWidth.asStateFlow()

    val min = 110
    val max = 300
    private const val STEP = 26

    /** Whether the side navigation is down to its icons. */
    private val _railCollapsed = MutableStateFlow(false)
    val railCollapsed: StateFlow<Boolean> = _railCollapsed.asStateFlow()

    private val prefs by lazy { settingsStore("library") }

    fun init() {
        _coverWidth.value = prefs.getInt(KEY_COVER, _coverWidth.value).coerceIn(min, max)
        _railCollapsed.value = prefs.getBoolean(KEY_RAIL, false)
    }

    fun setCoverWidth(width: Int) {
        val next = width.coerceIn(min, max)
        _coverWidth.value = next
        prefs.putInt(KEY_COVER, next)
    }

    fun toggleRail() {
        val next = !_railCollapsed.value
        _railCollapsed.value = next
        prefs.putBoolean(KEY_RAIL, next)
    }

    fun bigger() = setCoverWidth(_coverWidth.value + STEP)

    fun smaller() = setCoverWidth(_coverWidth.value - STEP)

    val canGrow: Boolean get() = _coverWidth.value < max
    val canShrink: Boolean get() = _coverWidth.value > min
}
