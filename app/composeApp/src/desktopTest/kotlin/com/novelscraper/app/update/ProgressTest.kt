package com.novelscraper.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressTest {
    /** A 150 MB update arrives in 64 KiB pieces: some 2,300 of them. The screen
     *  needs to hear of each percent, not each piece. */
    @Test fun aDownloadIsShownAPercentAtATime() {
        val total = 150L * 1024 * 1024
        val progress = Progress(total)
        var shown = 0
        var done = 0L
        while (done < total) {
            val n = minOf(65_536L, total - done)
            done += n
            if (progress.advance(n)) shown++
        }
        assertTrue(shown in 100..101, "shown $shown times")
    }

    @Test fun anUnknownSizeIsShownNowAndThen() {
        val progress = Progress(0)
        val shown = (1..2_400).count { progress.advance(65_536) }
        assertEquals(true, shown in 1..50, "shown $shown times")
    }
}
