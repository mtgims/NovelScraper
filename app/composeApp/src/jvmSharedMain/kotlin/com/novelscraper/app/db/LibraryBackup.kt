package com.novelscraper.app.db

import com.novelscraper.app.platform.Log
import java.io.File

/**
 * A copy of the library kept just before the app changes its shape.
 *
 * A schema migration rewrites the one file that holds everything the reader
 * owns, so the version on disk is copied aside first: if a migration ever goes
 * wrong, the library is one file copy away. The newest [KEEP] copies are kept.
 */
internal object LibraryBackup {

    const val KEEP = 2
    private const val TAG = "LibraryBackup"

    /** Copy [file] aside when it is about to be migrated from [onDiskVersion]
     *  up to [schemaVersion]. Returns the copy, if one was made. */
    fun beforeMigration(file: File, onDiskVersion: Long, schemaVersion: Long): File? {
        // 0 = a database that doesn't exist yet (nothing to lose).
        if (!file.exists() || onDiskVersion <= 0 || onDiskVersion >= schemaVersion) return null
        return try {
            val backup = File(file.parentFile, "${file.nameWithoutExtension}-v$onDiskVersion.bak")
            file.copyTo(backup, overwrite = true)
            Log.i(TAG, "library backed up before migrating v$onDiskVersion -> v$schemaVersion")
            prune(file)
            backup
        } catch (e: Exception) {
            // A backup that can't be written must not stop the app from opening.
            Log.w(TAG, "couldn't back up the library: ${e.message}")
            null
        }
    }

    /** Keep the newest [KEEP] copies. */
    private fun prune(file: File) {
        val backups = file.parentFile
            ?.listFiles { f -> f.name.startsWith("${file.nameWithoutExtension}-v") && f.name.endsWith(".bak") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        backups.drop(KEEP).forEach { runCatching { it.delete() } }
    }
}
