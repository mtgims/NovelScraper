package com.novelscraper.app.extensions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MissingCoversTest {
    private val asked = mutableListOf<String>()
    private val saved = MissingCovers.lookup

    @BeforeTest fun clear() = MissingCovers.forget()
    @AfterTest fun restore() { MissingCovers.lookup = saved; MissingCovers.forget() }

    @Test fun eachNovelIsAskedForOnceWhateverTheAnswer() = runBlocking {
        MissingCovers.lookup = { _, path ->
            synchronized(asked) { asked += path }
            when (path) {
                "a" -> "https://site/a.jpg"
                "broken" -> error("the page didn't load")
                else -> null
            }
        }
        repeat(2) {
            assertEquals("https://site/a.jpg", MissingCovers.of("src", "a"))
            assertNull(MissingCovers.of("src", "none"))
            assertNull(MissingCovers.of("src", "broken"))
        }
        assertEquals(listOf("a", "none", "broken"), asked, "scrolling back asks nothing again")
        MissingCovers.of("other", "a")
        assertEquals("a", asked.last(), "another source's novel of the same path is its own")
    }

    @Test fun oneLookupAtATimePerSource() = runBlocking {
        val running = AtomicInteger()
        var most = 0
        MissingCovers.lookup = { id, path ->
            val now = running.incrementAndGet()
            synchronized(asked) { most = maxOf(most, now); asked += "$id/$path" }
            delay(20)
            running.decrementAndGet()
            null
        }
        (1..5).map { async { MissingCovers.of("src", "n$it") } }.awaitAll()
        assertEquals(1, most)
        assertEquals(5, asked.size)
    }

    @Test fun aNovelScrolledAwayIsAskedForAgainNextTime() = runBlocking {
        val started = CompletableDeferred<Unit>()
        MissingCovers.lookup = { _, path ->
            synchronized(asked) { asked += path }
            started.complete(Unit)
            delay(10_000)
            "https://site/late.jpg"
        }
        val gone = launch { MissingCovers.of("src", "a") }
        started.await()
        gone.cancel()
        yield()
        MissingCovers.lookup = { _, path -> synchronized(asked) { asked += path }; "https://site/a.jpg" }
        assertEquals("https://site/a.jpg", MissingCovers.of("src", "a"))
        assertEquals(listOf("a", "a"), asked)
    }
}
