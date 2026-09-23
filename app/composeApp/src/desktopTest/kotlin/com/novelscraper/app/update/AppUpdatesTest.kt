package com.novelscraper.app.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdatesTest {

    @Test fun laterVersionsWin() {
        assertTrue(AppUpdates.isNewer("0.39.3", "0.39.2"))
        assertTrue(AppUpdates.isNewer("0.40.0", "0.39.9"))
        assertTrue(AppUpdates.isNewer("1.0.0", "0.99.99"))
    }

    @Test fun compareNumbersNotText() {
        // The two comparisons string order gets wrong, which is why this exists.
        assertTrue(AppUpdates.isNewer("0.40.0", "0.9.9"))
        assertTrue(AppUpdates.isNewer("0.39.10", "0.39.9"))
    }

    @Test fun theSameOrOlderIsNotAnUpdate() {
        assertFalse(AppUpdates.isNewer("0.39.3", "0.39.3"))
        assertFalse(AppUpdates.isNewer("0.39.2", "0.39.3"))
        assertFalse(AppUpdates.isNewer("0.9.9", "0.40.0"))
    }

    @Test fun oddVersionsDontThrow() {
        assertFalse(AppUpdates.isNewer("", "0.39.3"))
        assertTrue(AppUpdates.isNewer("0.40", "0.39.3"))
        assertFalse(AppUpdates.isNewer("garbage", "0.39.3"))
    }
}
