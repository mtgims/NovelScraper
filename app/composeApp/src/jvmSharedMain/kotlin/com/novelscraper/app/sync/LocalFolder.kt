package com.novelscraper.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A sync folder on this device's own disk: what same-Wi-Fi sync works from.
 * [FolderSync] reads and writes it as it would any folder, and [LanSync]
 * copies its files to and from the other devices when they meet, so a change
 * travels from device to device however they happen to meet.
 *
 * A write lands in a temporary file and is moved into place, so a file is
 * never seen half written, by a reader here or a device copying it.
 */
class LocalFolder(val dir: File) : SyncFolder {

    private val lock = Any()

    override suspend fun list(): List<RemoteFile> = io { listNow() }
    override suspend fun read(name: String): ByteArray? = io { readNow(name) }
    override suspend fun write(name: String, bytes: ByteArray): Unit = io { writeNow(name, bytes) }
    override suspend fun delete(name: String): Unit = io { deleteNow(name) }

    // The same, for the copying side ([LanSync]), already off the main thread.

    internal fun listNow(): List<RemoteFile> = synchronized(lock) {
        dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.map { RemoteFile(it.name, it.length()) }
    }

    internal fun readNow(name: String): ByteArray? = synchronized(lock) { file(name).takeIf { it.isFile }?.readBytes() }

    internal fun writeNow(name: String, bytes: ByteArray) {
        synchronized(lock) {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't make $dir")
            val tmp = File(dir, ".$name.tmp")
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), file(name).toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    internal fun deleteNow(name: String) { synchronized(lock) { file(name).delete() } }

    /** Everything goes: disconnecting. */
    fun clear() { synchronized(lock) { dir.deleteRecursively() } }

    private fun file(name: String): File {
        // Names come from other devices too: never a path.
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") { "bad name" }
        return File(dir, name)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
