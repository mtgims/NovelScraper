package com.novelscraper.app.platform

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceTest {

    private fun dir() = Files.createTempDirectory("instance").toFile()

    @Test
    fun aSecondCopyWakesTheFirstInsteadOfOpeningTheLibraryToo() {
        val d = dir()
        val first = SingleInstance(d)
        val second = SingleInstance(d)
        try {
            assertTrue(first.tryAcquire())
            val woken = AtomicInteger()
            first.watch { woken.incrementAndGet() }

            assertFalse(second.tryAcquire())
            assertTrue(second.wakeOther(3_000), "the first copy didn't answer")
            assertEquals(1, woken.get())
            assertFalse(second.wakeFile.exists())
        } finally {
            first.release(); second.release(); d.deleteRecursively()
        }
    }

    /** After an update the new copy starts while the old one is closing:
     *  it waits for the lock rather than giving up. */
    @Test
    fun theCopyAnUpdateStartsTakesOverOnceTheOldOneHasGone() {
        val d = dir()
        val old = SingleInstance(d)
        val new = SingleInstance(d)
        try {
            assertTrue(old.tryAcquire())
            Thread { Thread.sleep(600); old.release() }.start()
            assertTrue(new.acquire(5_000))
        } finally {
            old.release(); new.release(); d.deleteRecursively()
        }
    }

    /** A copy that has stopped answering (or is closing) is not woken
     *  forever: the request is withdrawn and the caller decides. */
    @Test
    fun anUnansweredWakeIsWithdrawn() {
        val d = dir()
        val stuck = SingleInstance(d)
        val second = SingleInstance(d)
        try {
            assertTrue(stuck.tryAcquire())   // never watches
            assertFalse(second.wakeOther(500))
            assertFalse(second.wakeFile.exists())
        } finally {
            stuck.release(); second.release(); d.deleteRecursively()
        }
    }

    /** The lock is the operating system's: it goes with the process, so a
     *  crash never leaves the app refusing to start. */
    @Test
    fun aLeftoverLockFileIsNotALock() {
        val d = dir()
        try {
            SingleInstance(d).lockFile.apply { parentFile.mkdirs(); writeText("12345") }
            val s = SingleInstance(d)
            assertTrue(s.tryAcquire())
            s.release()
        } finally {
            d.deleteRecursively()
        }
    }
}
