package com.novelscraper.app.data

import com.novelscraper.app.ui.ScrollSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where reading stopped in each chapter: kept, but not forever, and not every frame. */
class ScrollPositionsTest {

    @BeforeTest fun prefs() = ReaderPrefs.init()

    @Test fun onlyTheMostRecentChaptersKeepTheirPlace() {
        val novel = "L${System.nanoTime()}"
        for (p in 1..ReaderPrefs.MAX_SCROLL_KEYS + 1) ReaderPrefs.setScroll(novel, p, 0.5f)
        assertEquals(0f, ReaderPrefs.getScroll(novel, 1), "the oldest chapter's place was let go")
        assertEquals(0.5f, ReaderPrefs.getScroll(novel, 2))
        assertEquals(0.5f, ReaderPrefs.getScroll(novel, ReaderPrefs.MAX_SCROLL_KEYS + 1))

        // Reading an old chapter again makes it recent again.
        ReaderPrefs.setScroll(novel, 2, 0.7f)
        ReaderPrefs.setScroll(novel, 9_999, 0.1f)
        assertEquals(0.7f, ReaderPrefs.getScroll(novel, 2), "touched again, so kept")
        assertEquals(0f, ReaderPrefs.getScroll(novel, 3), "now the oldest, so let go")
    }

    @Test fun aBurstOfScrollingIsSavedOnceAndTheLastPlaceOnClose() {
        val saved = CopyOnWriteArrayList<Float>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val saver = ScrollSaver(scope, quietMs = 100) { saved += it }
        for (i in 1..60) { saver.update(i / 100f); Thread.sleep(2) }   // a second of frames, quickly
        Thread.sleep(400)
        assertEquals(listOf(0.6f), saved.toList(), "one save, of where the scrolling stopped")

        saver.update(0.9f)
        saver.flush()                                                 // the chapter closes at once
        assertEquals(listOf(0.6f, 0.9f), saved.toList())
        scope.cancel()
    }
}
