package com.novelscraper.app.library

import com.novelscraper.app.data.SyncChange
import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.ChapterItem
import com.novelscraper.app.extensions.SourceNovel
import com.novelscraper.app.sync.FakeFolder
import com.novelscraper.app.sync.KeyCache
import com.novelscraper.app.sync.SyncCodec
import com.novelscraper.app.sync.SyncFiles
import com.novelscraper.app.sync.SyncNotEncryptedException
import com.novelscraper.app.sync.SyncPassphraseNeededException
import com.novelscraper.app.sync.SyncWrongPassphraseException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Devices with their own libraries and clocks, syncing through one folder. */
class FolderSyncTest {

    private class Source : SourceOrigin {
        override suspend fun name(pluginId: String) = "Fake Source"
        override suspend fun novel(pluginId: String, path: String) = SourceNovel(
            name = "The Novel", path = path,
            chapters = (1..5).map { ChapterItem("Chapter $it", "$path/c$it") },
        )
        override suspend fun chapter(pluginId: String, path: String) = "<p>$path</p>"
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    private class Device(
        val folder: FakeFolder,
        var clock: Long = 1_000_000,
        var passphrase: String? = null,
        file: File? = null,
        server: ServerOrigin = LibraryStoreTest.offlineServer(),
    ) {
        private val keys = KeyCache()
        val lib = LibraryStore(jdbcLibraryDriver(file), Source(), server, now = { clock })
        suspend fun sync() = lib.syncFolder(folder, passphrase?.toCharArray(), keys)
        val id get() = lib.sync.device()
    }

    private val folder = FakeFolder()
    private val a = Device(folder)
    private val b = Device(folder)

    private suspend fun addNovel(d: Device): Int {
        val id = d.lib.openSource("fake", "n", "The Novel", null)
        d.lib.refresh(id)
        d.lib.setInLibrary(id, true)
        return id
    }

    private suspend fun libraryOf(d: Device) = d.lib.libraryFlow().first()

    @Test
    fun aNovelAndEverythingAboutItReachTheOtherDevice() = runBlocking<Unit> {
        val idA = addNovel(a)
        a.lib.markOpened(idA, 2)
        a.lib.saveProgress(idA, 2, 0.3f, 5)
        a.lib.setRating(idA, 4)
        val later = a.lib.createCollection("Later")
        a.lib.setBookCollections(idA, listOf(later))
        a.sync()

        b.sync()
        val novel = libraryOf(b).single()
        assertEquals("The Novel", novel.title)
        assertEquals(4, novel.rating)
        val shelf = b.lib.collectionsFlow().first().single()
        assertEquals("Later", shelf.name)
        assertEquals(listOf(shelf.id), novel.collectionIds)
        b.lib.refresh(novel.id)
        val p = b.lib.progress(novel.id)
        assertEquals(setOf(2), p.readPositions)
        assertEquals(2, p.lastPosition); assertEquals(0.3f, p.scroll); assertEquals(5, p.sentence)

        // And back: B's change reaches A.
        b.clock = 2_000_000; b.lib.setRating(novel.id, 2); b.sync()
        a.sync()
        assertEquals(2, a.lib.book(idA)!!.rating)
    }

    @Test
    fun concurrentEditsConverge() = runBlocking<Unit> {
        val idA = addNovel(a); a.sync()
        b.sync()
        val idB = libraryOf(b).single().id
        b.lib.refresh(idB)

        folder.offline = true
        a.clock = 2_000_000; a.lib.setRead(idA, listOf(3), true)
        b.clock = 3_000_000; b.lib.setRead(idB, listOf(3), true)
        b.clock = 3_000_001; b.lib.setRead(idB, listOf(3), false)
        a.clock = 5_000_000; a.lib.setRating(idA, 5)
        b.clock = 4_000_000; b.lib.setRating(idB, 2)
        assertFailsWith<IOException> { a.sync() }
        folder.offline = false

        a.sync(); b.sync(); a.sync()
        for ((d, id) in listOf(a to idA, b to idB)) {
            assertFalse(3 in d.lib.progress(id).readPositions, "chapter 3: the later unmark wins")
            assertEquals(5, d.lib.book(id)!!.rating, "the later rating wins")
        }
    }

    @Test
    fun anUploadNeverRecordedIsWrittenAgainNotTwice() = runBlocking<Unit> {
        val idA = addNovel(a); a.sync()
        val before = a.id
        a.clock = 2_000_000; a.lib.setRating(idA, 3)
        folder.failAfterWrite = true
        assertFailsWith<IOException> { a.sync() }
        a.sync()

        assertEquals(before, a.id, "its own unfinished upload is not another copy")
        assertEquals(listOf(1L to true, 2L to false), folder.batchesOf(before).map { it.seq to it.snapshot }.sortedBy { it.first },
            "batch 2 was rewritten under its own name")
        b.sync()
        assertEquals(3, libraryOf(b).single().rating)
    }

    @Test
    fun oldBatchesAreCompactedAndLateDevicesCatchUp() = runBlocking<Unit> {
        val idA = addNovel(a); a.sync()
        b.sync()
        repeat(60) { i ->
            a.clock += 1_000
            a.lib.setRating(idA, i % 5 + 1)
            a.sync()
            if (i == 20) b.sync()
        }
        val batches = folder.batchesOf(a.id)
        val snap = batches.single { it.snapshot }
        assertTrue(snap.seq > 1, "a newer snapshot replaced the first")
        assertTrue(batches.filter { !it.snapshot }.all { it.seq > snap.seq }, "batches it covers are gone: $batches")

        b.sync()
        assertEquals(a.lib.book(idA)!!.rating, libraryOf(b).single().rating, "B, behind the snapshot, caught up")
        val c = Device(folder); c.sync()
        assertEquals(a.lib.book(idA)!!.rating, libraryOf(c).single().rating, "a new device gets everything")
    }

    @Test
    fun aBatchVanishingMidReadIsReadNextTime() = runBlocking<Unit> {
        addNovel(a); a.sync()
        folder.vanishOnce += folder.files.keys.filter { SyncFiles.parse(it)?.device == a.id }
        b.sync()
        assertTrue(libraryOf(b).isEmpty())
        b.sync()
        assertEquals(1, libraryOf(b).size)
    }

    @Test
    fun damagedAndForeignFilesChangeNothing() = runBlocking<Unit> {
        val idA = addNovel(a); a.lib.setRating(idA, 4); a.sync()
        val aFile = folder.files.keys.single { SyncFiles.parse(it)?.device == a.id }
        folder.files["notes.txt"] = "hello".toByteArray()
        folder.files["x.0000000001.seg"] = byteArrayOf(1, 2, 3)
        // A real batch under a name its header does not match.
        folder.files["y.0000000001.snap"] = folder.files.getValue(aFile)
        // A batch from a newer format, which this version must not guess at.
        folder.files["z.0000000001.seg"] = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).bufferedWriter().use {
                it.write("""{"v":99,"device":"z","seq":1}""" + "\n" +
                    """{"kind":"rating","key":"src:fake\tn","value":"{\"rating\":1}","ts":9000000,"device":"z"}""" + "\n")
            }
        }.toByteArray()
        b.sync()
        assertEquals(4, libraryOf(b).single().rating)
    }

    @Test
    fun anEncryptedFolderOpensOnlyWithItsPassphrase() = runBlocking<Unit> {
        a.passphrase = "correct horse"
        val idA = addNovel(a); a.lib.setRating(idA, 4); a.sync()
        for ((name, bytes) in folder.files) if (name != SyncFiles.MANIFEST)
            assertFalse("The Novel".toByteArray().let { t -> bytes.toList().windowed(t.size).any { it == t.toList() } },
                "$name is sealed")

        b.passphrase = "correct horse"; b.sync()
        assertEquals(4, libraryOf(b).single().rating)

        assertFailsWith<SyncWrongPassphraseException> { Device(folder, passphrase = "wrong").sync() }
        assertFailsWith<SyncPassphraseNeededException> { Device(folder).sync() }

        // Someone who can write to the folder but has no key cannot edit a library.
        val forged = SyncChange("rating", "src:fake\tn", """{"rating":1}""", 9_000_000, "evil")
        folder.files[SyncFiles.name("evil", 1, snapshot = false)] = SyncCodec(null).encode("evil", 1, "n", listOf(forged))
        b.sync()
        assertEquals(4, libraryOf(b).single().rating)
    }

    @Test
    fun aPassphraseIsRefusedForAPlainFolder() = runBlocking<Unit> {
        addNovel(a); a.sync()
        b.passphrase = "late"
        assertFailsWith<SyncNotEncryptedException> { b.sync() }
    }

    @Test
    fun aCopiedLibraryTakesItsOwnIdentity() = runBlocking<Unit> {
        val fileA = Files.createTempFile("folder-a", ".db").toFile()
        val fileB = File(fileA.path + ".copy")
        try {
            val a = Device(folder, file = fileA)
            val idA = addNovel(a); a.sync()
            // A restored backup: the same database, on another device.
            DriverManager.getConnection("jdbc:sqlite:${fileA.absolutePath}").use { c ->
                c.createStatement().use { it.execute("VACUUM INTO '${fileB.absolutePath}'") }
            }
            val copy = Device(folder, file = fileB)
            assertEquals(a.id, copy.id)
            val idCopy = libraryOf(copy).single().id

            a.clock = 2_000_000; a.lib.setRating(idA, 3); a.sync()
            copy.clock = 3_000_000; copy.lib.setRating(idCopy, 5); copy.sync()
            assertNotEquals(a.id, copy.id, "the copy noticed and took a new id")

            a.sync(); b.sync()
            for (d in listOf(a, copy, b)) assertEquals(5, libraryOf(d).single().rating, "the later rating everywhere")
            copy.clock = 4_000_000; copy.lib.setRating(idCopy, 1); copy.sync()
            a.sync()
            assertEquals(1, a.lib.book(idA)!!.rating, "the copy keeps syncing under its new id")
        } finally {
            fileA.delete(); fileB.delete()
        }
    }

    @Test
    fun aLibrarySyncedThroughTheServerMovesWhole() = runBlocking<Unit> {
        val server = LibraryStoreTest.FakeSync()
        val old = Device(folder, server = LibraryStoreTest.FakeServer(server))
        val mover = Device(folder, server = LibraryStoreTest.FakeServer(server))
        val idOld = addNovel(old); old.lib.setRating(idOld, 4); old.lib.syncNow()
        mover.lib.syncNow()
        assertEquals(1, libraryOf(mover).size)

        // The mover switches to the folder; the old device never does.
        mover.sync()
        val fresh = Device(folder); fresh.sync()
        assertEquals(4, libraryOf(fresh).single().rating, "what the server gave the mover arrives whole")
    }
}
