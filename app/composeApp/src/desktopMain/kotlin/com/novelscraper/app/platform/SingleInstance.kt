package com.novelscraper.app.platform

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * One copy of the app per data folder. Two copies on one library each have
 * their own connection to it and their own idea of what it holds, and a write
 * from one while the other holds the write lock fails at once ("database is
 * locked", seen 2026-11-15 with two starts a minute apart).
 *
 * The first copy holds a lock on [lockFile] for as long as it runs; the
 * operating system lets go of it however the process ends, a crash included.
 * A second copy asks the first to come to the front by creating [wakeFile],
 * waits for it to be taken away (the answer), and quits. After an update the
 * new copy is started while the old one is still closing, so it waits for
 * the lock instead of waking a window that is about to go.
 */
class SingleInstance(dir: File) {
    val lockFile = File(dir, "instance.lock")
    val wakeFile = File(dir, "instance.wake")
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** Takes the lock if no other copy holds it. */
    @Synchronized
    fun tryAcquire(): Boolean {
        if (lock != null) return true
        lockFile.parentFile?.mkdirs()
        val ch = RandomAccessFile(lockFile, "rw").channel
        val got = try {
            ch.tryLock()
        } catch (_: OverlappingFileLockException) {
            null   // held by this same process (tests)
        } catch (_: java.io.IOException) {
            null
        }
        if (got == null) { ch.close(); return false }
        channel = ch
        lock = got
        wakeFile.delete()   // a request nobody answered, from before
        return true
    }

    /** Waits up to [timeoutMs] for the lock (after an update, the old copy
     *  closing). */
    fun acquire(timeoutMs: Long): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (tryAcquire()) return true
            if (System.currentTimeMillis() >= until) return false
            Thread.sleep(250)
        }
    }

    /**
     * Asks the copy holding the lock to come to the front. True once it has
     * answered (taken [wakeFile] away). False when it didn't answer in
     * [timeoutMs], or the lock came free meanwhile, which [tryAcquire] then
     * takes.
     */
    fun wakeOther(timeoutMs: Long): Boolean {
        runCatching { wakeFile.writeText(ProcessHandle.current().pid().toString()) }
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (!wakeFile.exists()) return true
            if (tryAcquire()) return false
            Thread.sleep(100)
        }
        wakeFile.delete()
        return false
    }

    /** In the copy holding the lock: calls [onWake] (on a background thread)
     *  each time another copy asks, after answering it. */
    fun watch(onWake: () -> Unit) {
        Thread {
            while (true) {
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (wakeFile.exists() && wakeFile.delete()) runCatching(onWake)
            }
        }.apply { isDaemon = true; name = "single-instance"; start() }
    }

    @Synchronized
    fun release() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }

    companion object {
        /** Passed to the copy an update starts. */
        const val AFTER_UPDATE_ARG = "--after-update"
    }
}
