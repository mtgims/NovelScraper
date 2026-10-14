package com.novelscraper.app.sync

import com.novelscraper.app.library.SyncRecords
import com.novelscraper.app.platform.Log
import java.io.IOException
import java.util.UUID
import javax.crypto.SecretKey

/** Runs a block in one database transaction, off the main thread. */
internal interface SyncTx {
    suspend fun <T> run(block: () -> T): T
}

/**
 * Library sync through a folder ([SyncFolder]) instead of a server.
 *
 * The records and their merging are [SyncRecords]'s, unchanged: last writer
 * wins per record, so applying a record twice, or an old one late, changes
 * nothing. What this adds is the transport:
 *
 * - **Push.** What changed here goes out as the next numbered batch,
 *   `<me>.<seq>.seg`. Only this device writes files with its id, so nothing
 *   another device does can clash with it. An upload that succeeds but is not
 *   recorded here (the app dies in between) is written again under the same
 *   name, so a retry never duplicates.
 * - **Pull.** Every other device's batches, in order from where this device
 *   last stopped reading it, each applied with its cursor in one transaction. A
 *   gap (a batch not there, or not readable) stops that device's reading until
 *   its next snapshot fills it.
 * - **Snapshots.** Every record this device knows, whoever wrote it, as
 *   `<me>.<seq>.snap`: replayed anywhere, it rebuilds the library. Written on a
 *   device's first sync with a folder (which is how a library synced through
 *   the server arrives whole), when there is too much to send as one batch, and
 *   after [COMPACT_AFTER] batches, after which the device's older files go.
 *   A reader behind a device's newest snapshot reads that first.
 */
internal class FolderSync(
    private val records: SyncRecords,
    private val tx: SyncTx,
    private val folder: SyncFolder,
    private val passphrase: CharArray?,
    private val keys: KeyCache,
    /** A sync code's key ([PairKeys]), in place of a passphrase. */
    private val codeKey: SecretKey? = null,
) {
    private class Entry(val batch: BatchName, val file: RemoteFile)

    suspend fun run() {
        val listing = folder.list()
        val codec = SyncCodec(openManifest(listing))
        val files = listing.mapNotNull { f -> SyncFiles.parse(f.name)?.let { Entry(it, f) } }
        tx.run {
            records.enroll()
            // First sync with this folder: it starts from the whole library.
            if (records.snapSeq() == 0L) records.fillGaps()
        }
        val renamed = takeNewIdIfCloned(codec, files)
        val (me, snapSeq) = tx.run { records.device() to records.snapSeq() }
        val own = files.filter { it.batch.device == me }
        // This device's snapshot is gone (the folder was emptied, or files
        // were deleted by hand): without a new one, a device arriving later
        // could not read past the hole.
        val lost = snapSeq > 0 && own.none { it.batch.snapshot && it.batch.seq == snapSeq }
        pull(codec, files.filter { it.batch.device != me })
        push(codec, me, own, snapshot = renamed || lost)
    }

    // --- the manifest ------------------------------------------------------------

    /** This folder's key (null: not encrypted), writing the manifest if the
     *  folder is new. */
    private suspend fun openManifest(listing: List<RemoteFile>): SecretKey? {
        var manifests = listing.filter { it.name == SyncFiles.MANIFEST }
        if (manifests.isEmpty()) {
            val (m, _) = SyncCodec.newManifest(passphrase, codeKey)
            folder.write(SyncFiles.MANIFEST, SyncCodec.manifestJson(m))
            // Read back rather than trust what was written: another device may
            // have set the folder up in the same moment, and every device has to
            // agree on one manifest.
            manifests = folder.list().filter { it.name == SyncFiles.MANIFEST }
        }
        // Storage that allows two files of one name (Drive) may hold two; the
        // lowest id is the one every device picks.
        val first = manifests.minByOrNull { it.id } ?: throw IOException("the sync folder has no manifest")
        val bytes = folder.read(first.name) ?: throw IOException("the sync folder's manifest vanished")
        val m = SyncCodec.parseManifest(bytes) ?: throw IOException("the sync folder's manifest is damaged")
        return SyncCodec.keyFor(m, passphrase, keys, codeKey)
    }

    // --- one database, two devices -----------------------------------------------

    /**
     * Another copy of this database is writing under this device's id: a
     * restored backup, a data folder copied to a second computer. A batch past
     * the next one this device would write can only be that copy's. The next
     * one itself is either this device's own upload that was never recorded
     * (the app died in between) or the copy's, and the nonce recorded before
     * each upload tells them apart.
     *
     * This copy then takes a new id and starts with a snapshot, which
     * re-publishes everything it knows. The old id's files stay the other
     * copy's, read like any other device's.
     */
    private suspend fun takeNewIdIfCloned(codec: SyncCodec, files: List<Entry>): Boolean {
        val (me, outSeq, nonce) = tx.run { Triple(records.device(), records.outSeq(), records.outNonce()) }
        val mine = files.filter { it.batch.device == me && it.batch.seq > outSeq }
        val clash = mine.any { it.batch.seq > outSeq + 1 } || mine.any { e ->
            val written = folder.read(e.file.name) ?: return@any false
            val n = runCatching { codec.decode(e.batch, written).nonce }.getOrNull()
            n == null || n != nonce
        }
        if (!clash) return false
        val fresh = tx.run { records.newDevice().also { records.setPeerCursor(me, 0) } }
        Log.w(TAG, "another copy of this library writes as $me; this one is $fresh now")
        return true
    }

    // --- pull ----------------------------------------------------------------------

    private suspend fun pull(codec: SyncCodec, others: List<Entry>) {
        val cursors = tx.run { records.peerCursors() }
        for ((device, entries) in others.groupBy { it.batch.device }) {
            var at = cursors[device] ?: 0L
            val snap = entries.filter { it.batch.snapshot }.maxByOrNull { it.batch.seq }
            if (snap != null && snap.batch.seq > at) {
                if (!apply(codec, snap)) continue
                at = snap.batch.seq
            }
            for (e in entries.filter { !it.batch.snapshot && it.batch.seq > at }.sortedBy { it.batch.seq }) {
                if (e.batch.seq != at + 1) break
                if (!apply(codec, e)) break
                at = e.batch.seq
            }
        }
    }

    /** Apply one batch and move its device's cursor past it. False if it could
     *  not be read; that device is read no further this time. */
    private suspend fun apply(codec: SyncCodec, e: Entry): Boolean {
        val bytes = folder.read(e.file.name) ?: return false   // compacted away meanwhile
        val batch = try {
            codec.decode(e.batch, bytes)
        } catch (x: BadBatchException) {
            Log.w(TAG, "skipping ${e.file.name}: ${x.message}")
            return false
        }
        tx.run {
            records.applyIncoming(batch.changes)
            records.setPeerCursor(batch.device, batch.seq)
        }
        return true
    }

    // --- push ----------------------------------------------------------------------

    private suspend fun push(codec: SyncCodec, me: String, own: List<Entry>, snapshot: Boolean) {
        var force = snapshot
        // This device's files, as (name, seq): the ones listed, and the ones
        // written since, which a snapshot later in this run covers too.
        val mine = own.mapTo(ArrayList()) { it.file.name to it.batch.seq }
        repeat(MAX_ROUNDS) {
            val state = tx.run { State(records.outSeq(), records.snapSeq(), records.pendingCount()) }
            val seq = state.outSeq + 1
            if (force || state.snapSeq == 0L || state.pending > SEGMENT_MAX ||
                state.outSeq - state.snapSeq >= COMPACT_AFTER
            ) {
                val (all, sent) = tx.run { records.snapshot() to records.pending(Long.MAX_VALUE) }
                val nonce = announce()
                val name = SyncFiles.name(me, seq, snapshot = true)
                folder.write(name, codec.encode(me, seq, nonce, all))
                tx.run { records.sent(sent); records.setOutSeq(seq); records.setSnapSeq(seq) }
                force = false
                prune(mine, before = seq)
                mine += name to seq
                return@repeat
            }
            val out = tx.run { records.pending(SEGMENT_MAX) }
            if (out.isEmpty()) return
            val nonce = announce()
            val name = SyncFiles.name(me, seq, snapshot = false)
            folder.write(name, codec.encode(me, seq, nonce, out))
            tx.run { records.sent(out); records.setOutSeq(seq) }
            mine += name to seq
        }
    }

    /** A fresh nonce for the next upload, recorded before it starts. */
    private suspend fun announce(): String =
        UUID.randomUUID().toString().also { n -> tx.run { records.setOutNonce(n) } }

    private data class State(val outSeq: Long, val snapSeq: Long, val pending: Long)

    /** This device's files older than its newest snapshot, which covers them. */
    private suspend fun prune(mine: MutableList<Pair<String, Long>>, before: Long) {
        val old = mine.filter { it.second < before }
        for ((name, _) in old) {
            try {
                folder.delete(name)
            } catch (x: IOException) {
                // Harmless: the next snapshot's pruning takes it.
                Log.d(TAG, "could not delete $name: ${x.message}")
            }
        }
        mine -= old.toSet()
    }

    companion object {
        private const val TAG = "FolderSync"
        /** Records per batch file; more pending than this goes as a snapshot. */
        const val SEGMENT_MAX = 2_000L
        /** Batches after which a device writes a snapshot and drops its older files. */
        const val COMPACT_AFTER = 50L
        private const val MAX_ROUNDS = 40
    }
}
