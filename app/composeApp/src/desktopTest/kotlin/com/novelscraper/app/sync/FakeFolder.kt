package com.novelscraper.app.sync

import java.io.IOException

/** A sync folder in memory, shared by the devices of a test, with the ways a
 *  real one fails. */
class FakeFolder : SyncFolder {
    val files = LinkedHashMap<String, ByteArray>()
    var offline = false
    /** The next write lands, and then the call fails (the app died after the
     *  upload, before recording it). */
    var failAfterWrite = false
    /** Names that read as missing once, as if compacted away after the list. */
    val vanishOnce = HashSet<String>()

    private fun reachable() { if (offline) throw IOException("offline") }

    override suspend fun list(): List<RemoteFile> {
        reachable()
        return files.map { (name, bytes) -> RemoteFile(name, bytes.size.toLong()) }
    }

    override suspend fun read(name: String): ByteArray? {
        reachable()
        if (vanishOnce.remove(name)) return null
        return files[name]
    }

    override suspend fun write(name: String, bytes: ByteArray) {
        reachable()
        files[name] = bytes
        if (failAfterWrite) { failAfterWrite = false; throw IOException("connection lost") }
    }

    override suspend fun delete(name: String) {
        reachable()
        files.remove(name)
    }

    /** Batch files of [device], parsed. */
    internal fun batchesOf(device: String) = files.keys.mapNotNull(SyncFiles::parse).filter { it.device == device }
}
