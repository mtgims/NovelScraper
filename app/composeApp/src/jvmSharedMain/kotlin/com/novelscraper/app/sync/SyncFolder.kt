package com.novelscraper.app.sync

/**
 * A folder in the user's own storage that the library syncs through (Google
 * Drive's app folder, Dropbox's, OneDrive's, a WebDAV directory). Flat: names
 * only, no subfolders, so one [list] sees everything.
 *
 * Every device writes only files named with its own id ([SyncFiles]), so no two
 * devices ever write the same file and storage without locks is enough.
 *
 * Calls throw [java.io.IOException] when the storage can't be reached, and
 * [FolderAuthException] when it refuses the saved sign-in.
 */
interface SyncFolder {
    suspend fun list(): List<RemoteFile>
    /** The file's bytes, or null if it is not there (any more). */
    suspend fun read(name: String): ByteArray?
    /** Create or replace. */
    suspend fun write(name: String, bytes: ByteArray)
    /** Gone afterwards; a missing file is not an error. */
    suspend fun delete(name: String)
}

/** [id]: the storage's own id where it has one (Drive), else the name. */
data class RemoteFile(val name: String, val size: Long, val id: String = name)

/** The storage answered, but no longer accepts the saved sign-in. */
class FolderAuthException(message: String) : java.io.IOException(message)

/** A batch file's name, parsed. */
internal data class BatchName(val device: String, val seq: Long, val snapshot: Boolean)

/**
 * File names in the folder:
 *
 *     novelscraper.json        the manifest (format, encryption)
 *     <device>.<seq>.seg       a batch of records the device wrote
 *     <device>.<seq>.snap      every record the device knew, as of its batch seq
 *
 * seq is zero-padded to ten digits, so names sort in batch order.
 */
internal object SyncFiles {
    const val MANIFEST = "novelscraper.json"
    private const val SEG = "seg"
    private const val SNAP = "snap"

    fun name(device: String, seq: Long, snapshot: Boolean) =
        "$device.${seq.toString().padStart(10, '0')}.${if (snapshot) SNAP else SEG}"

    /** Null for anything that is not a batch file (the manifest, strangers). */
    fun parse(name: String): BatchName? {
        val parts = name.split('.')
        if (parts.size != 3) return null
        val (device, seq, ext) = parts
        if (device.isEmpty() || seq.length != 10 || !seq.all { it in '0'..'9' }) return null
        return when (ext) {
            SEG -> BatchName(device, seq.toLong(), false)
            SNAP -> BatchName(device, seq.toLong(), true)
            else -> null
        }
    }
}
