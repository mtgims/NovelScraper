package com.novelscraper.app.library

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncSoonTest {
    /** While narration plays in the background, progress is saved every 20 s and
     *  each save asks for a sync. Asked many times while the loop is asleep, a
     *  sync used to start for every ask. */
    @Test fun manyAsksInAMomentStartOneSync() {
        val before = LibrarySyncRunner.runs.get()
        repeat(20) { LibrarySyncRunner.soon() }
        Thread.sleep(3_000)
        assertEquals(1, LibrarySyncRunner.runs.get() - before)
    }
}
