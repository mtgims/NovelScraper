package com.novelscraper.app.extensions

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A source's storage: saved, but not re-encoded whole on every single change. */
class PluginStorageTest {

    @Test fun aBurstOfChangesIsSavedOnceOrTwiceAndInFull() {
        val saves = AtomicInteger()
        val saved = AtomicReference<Map<String, String>>(emptyMap())
        val map = Extensions.PersistentMap(emptyMap()) { m ->
            Thread.sleep(5)                     // a save takes a moment, as encoding and writing do
            saved.set(m); saves.incrementAndGet()
        }
        repeat(100) { map["k$it"] = "v$it" }
        map.remove("k0")
        // Settle: whatever is pending is written.
        var last = -1
        while (saves.get() != last) { last = saves.get(); Thread.sleep(200) }

        assertTrue(saves.get() < 10, "101 changes, ${saves.get()} saves")
        assertEquals(99, saved.get().size, "the last save holds every change")
        assertEquals("v99", saved.get()["k99"])
        assertTrue("k0" !in saved.get())
    }

    @Test fun settingTheSameValueAgainSavesNothing() {
        val saves = AtomicInteger()
        val map = Extensions.PersistentMap(mapOf("token" to "abc")) { saves.incrementAndGet() }
        map["token"] = "abc"
        Thread.sleep(200)
        assertEquals(0, saves.get())
    }
}
