package com.novelscraper.app.library

import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.ChapterItem
import com.novelscraper.app.extensions.SourceNovel
import com.novelscraper.app.sync.KeyCache
import com.novelscraper.app.sync.LanSync
import com.novelscraper.app.sync.LocalFolder
import com.novelscraper.app.sync.PairKeys
import com.novelscraper.app.sync.Replication
import com.novelscraper.app.sync.SyncCode
import com.novelscraper.app.sync.SyncFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Devices on one network, each with its own library and sync folder, meeting over loopback. */
class LanSyncTest {

    private class Source : SourceOrigin {
        override suspend fun name(pluginId: String) = "Fake Source"
        override suspend fun novel(pluginId: String, path: String) = SourceNovel(
            name = "The Novel", path = path,
            chapters = (1..5).map { ChapterItem("Chapter $it", "$path/c$it") },
        )
        override suspend fun chapter(pluginId: String, path: String) = "<p>$path</p>"
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    private val started = ArrayList<LanSync>()
    private val dirs = ArrayList<java.io.File>()

    private inner class Device(
        code: SyncCode,
        var clock: Long = 1_000_000,
        discoveryPort: Int = 0,
        targets: List<InetSocketAddress> = emptyList(),
    ) {
        val lib = LibraryStore(jdbcLibraryDriver(null), Source(), LibraryStoreTest.offlineServer(), now = { clock })
        val folder = LocalFolder(Files.createTempDirectory("lan").toFile().also { dirs += it })
        private val keys = PairKeys(code)
        private val cache = KeyCache()
        @Volatile var received = 0

        /** Wait for the other side's notice that something arrived (its own thread). */
        fun awaitReceived(atLeast: Int = 1, ms: Long = 5_000) {
            val until = System.currentTimeMillis() + ms
            while (received < atLeast && System.currentTimeMillis() < until) Thread.sleep(20)
        }
        val lan = LanSync(
            folder, keys, runBlocking { lib.syncDevice() }, "device", onReceived = { received++ },
            discoveryPort = discoveryPort, serverPort = 0, extraTargets = targets,
        ).also { it.start(); started += it }

        /** What the runner does: sync the library with its own folder. */
        suspend fun sync() = lib.syncFolder(folder, null, cache, keys.key)

        fun meet(other: Device) = lan.exchange("127.0.0.1", other.lan.port)
    }

    @AfterTest
    fun stop() {
        started.forEach { it.stop() }
        dirs.forEach { it.deleteRecursively() }
    }

    private val code = SyncCode.generate()

    private suspend fun addNovel(d: Device): Int {
        val id = d.lib.openSource("fake", "n", "The Novel", null)
        d.lib.refresh(id)
        d.lib.setInLibrary(id, true)
        return id
    }

    private suspend fun libraryOf(d: Device) = d.lib.libraryFlow().first()

    @Test
    fun aNovelAddedOnOneDeviceArrivesOnTheOther() = runBlocking<Unit> {
        val a = Device(code); val b = Device(code)
        val idA = addNovel(a); a.lib.setRating(idA, 4); a.sync()
        b.sync()
        assertTrue(a.meet(b))
        b.awaitReceived()
        assertEquals(1, b.received, "B was told something arrived")
        b.sync()
        assertEquals(4, libraryOf(b).single().rating)

        // And back, B having changed it.
        b.clock = 2_000_000; b.lib.setRating(libraryOf(b).single().id, 2); b.sync()
        assertTrue(b.meet(a))
        a.sync()
        assertEquals(2, a.lib.book(idA)!!.rating)
    }

    /** The library screen watches libraryFlow: a change sync applies must show there. */
    @Test
    fun aChangeAppliedBySyncReachesTheLibraryOnScreen() = runBlocking<Unit> {
        val a = Device(code); val b = Device(code)
        val idA = addNovel(a); a.sync(); a.meet(b); b.sync()
        val seen = java.util.concurrent.CopyOnWriteArrayList<Int?>()
        val watcher = launch(kotlinx.coroutines.Dispatchers.IO) {
            b.lib.libraryFlow().collect { list -> seen += list.singleOrNull()?.rating }
        }
        kotlinx.coroutines.delay(300)
        a.clock = 2_000_000; a.lib.setRating(idA, 3); a.sync(); a.meet(b); b.sync()
        val until = System.currentTimeMillis() + 3_000
        while (seen.lastOrNull() != 3 && System.currentTimeMillis() < until) kotlinx.coroutines.delay(50)
        watcher.cancel()
        assertEquals(3, seen.lastOrNull(), "emissions: $seen")
    }

    @Test
    fun aChangeTravelsThroughADeviceInBetween() = runBlocking<Unit> {
        val a = Device(code); val b = Device(code); val c = Device(code)
        addNovel(a); a.sync()
        a.meet(b)            // A and C never meet.
        b.sync()
        b.meet(c)
        c.sync()
        assertEquals("The Novel", libraryOf(c).single().title)
    }

    @Test
    fun editsMadeApartConvergeOnTheNewest() = runBlocking<Unit> {
        val a = Device(code); val b = Device(code)
        val idA = addNovel(a); a.sync(); a.meet(b); b.sync()
        val idB = libraryOf(b).single().id
        // Apart: both rate it, B later.
        a.clock = 2_000_000; a.lib.setRating(idA, 1); a.sync()
        b.clock = 3_000_000; b.lib.setRating(idB, 5); b.sync()
        a.meet(b); a.sync(); b.sync()
        assertEquals(5, a.lib.book(idA)!!.rating)
        assertEquals(5, b.lib.book(idB)!!.rating)
    }

    @Test
    fun aDeviceWithoutTheCodeGetsNothing() = runBlocking<Unit> {
        val a = Device(code); val stranger = Device(SyncCode.generate())
        addNovel(a); a.sync()
        assertFalse(stranger.meet(a), "the stranger can't prove the code")
        assertFalse(a.meet(stranger), "nor can it answer for it")
        assertTrue(stranger.folder.listNow().none { SyncFiles.parse(it.name) != null })
        assertTrue(a.folder.listNow().none { SyncFiles.parse(it.name)?.device == stranger.lan.device })
    }

    @Test
    fun coveredBatchesAreDroppedEverywhere() = runBlocking<Unit> {
        val a = Device(code); val b = Device(code)
        val idA = addNovel(a); a.sync(); a.meet(b)
        repeat(55) { a.clock += 1_000; a.lib.setRating(idA, it % 5 + 1); a.sync() }
        a.meet(b)
        val onB = b.folder.listNow().mapNotNull { SyncFiles.parse(it.name) }.filter { it.device == a.lan.device }
        val snap = onB.single { it.snapshot }
        assertTrue(onB.all { it.seq >= snap.seq }, "B keeps only what A's newest snapshot doesn't cover: $onB")
        b.sync()
        assertEquals(a.lib.book(idA)!!.rating, libraryOf(b).single().rating)
    }

    @Test
    fun devicesFindEachOtherByTheirBroadcasts() = runBlocking<Unit> {
        val portA = freeUdpPort(); val portB = freeUdpPort()
        val loop = InetAddress.getLoopbackAddress()
        // Each one's announcements reach the other (loopback stands in for the Wi-Fi).
        val a = Device(code, discoveryPort = portA, targets = listOf(InetSocketAddress(loop, portB)))
        val b = Device(code, discoveryPort = portB, targets = listOf(InetSocketAddress(loop, portA)))
        // Within an announcement or two each has heard the other and met it.
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline &&
            (a.lan.state.value.peers.none { it.lastSync > 0 } || b.lan.state.value.peers.none { it.lastSync > 0 })
        ) Thread.sleep(100)
        assertTrue(a.lan.state.value.peers.any { it.device == b.lan.device && it.address.isNotEmpty() }, "A knows where B is")
        assertTrue(b.lan.state.value.peers.any { it.device == a.lan.device }, "B knows A")
        // A change on A then reaches B as the runner hands it on after a sync.
        addNovel(a); a.sync(); a.lan.exchangeAll()
        b.awaitReceived()
        b.sync()
        assertEquals(1, libraryOf(b).size)
    }

    @Test
    fun theCopyRulesKeepTheWriterInCharge() {
        fun e(name: String, sha: String = "x") = Replication.Entry(name, 1, sha)
        val seg = SyncFiles.name("w", 3, snapshot = false)
        val floors = mapOf("w" to 2L)
        // Missing here: take it.
        assertEquals(listOf(seg), Replication.wanted(emptyMap(), "me", listOf(e(seg)), "other", floors).map { it.name })
        // A different copy: only from the device that wrote it.
        assertTrue(Replication.wanted(mapOf(seg to e(seg, "old")), "me", listOf(e(seg, "new")), "other", floors).isEmpty())
        assertEquals(1, Replication.wanted(mapOf(seg to e(seg, "old")), "me", listOf(e(seg, "new")), "w", floors).size)
        // Covered by a newer snapshot: neither copied nor kept.
        val old = SyncFiles.name("w", 1, snapshot = false)
        assertTrue(Replication.wanted(emptyMap(), "me", listOf(e(old)), "w", floors).isEmpty())
        assertEquals(listOf(old), Replication.prunable(listOf(e(old), e(seg)), "me", floors))
        // Its own batches come back only if numbered past everything it wrote.
        val mine = mapOf(SyncFiles.name("me", 5, true) to e(SyncFiles.name("me", 5, true)))
        assertTrue(Replication.wanted(mine, "me", listOf(e(SyncFiles.name("me", 4, false))), "w", emptyMap()).isEmpty())
        assertEquals(1, Replication.wanted(mine, "me", listOf(e(SyncFiles.name("me", 9, false))), "w", emptyMap()).size)
        // The manifest is each device's own.
        assertTrue(Replication.wanted(emptyMap(), "me", listOf(e(SyncFiles.MANIFEST)), "w", emptyMap()).isEmpty())
    }

    /**
     * Discovery is a broadcast, so two devices usually hear each other within
     * moments and both connect. Each side used to hold its one lock while
     * waiting on the other's, so both sat out the full read timeout (20 s),
     * the meeting failed, and the next announcement started it all again.
     */
    @Test
    fun twoDevicesMeetingAtOnceDoNotStallEachOther() = runBlocking<Unit> {
        val a = Device(code); val b = Device(code)
        for ((d, path) in listOf(a to "na", b to "nb")) {
            val id = d.lib.openSource("fake", path, "Novel $path", null)
            d.lib.refresh(id); d.lib.setInLibrary(id, true); d.sync()
        }
        repeat(3) { round ->
            val go = java.util.concurrent.CountDownLatch(1)
            val took = LongArray(2)
            val meetings = listOf(a to b, b to a).mapIndexed { i, (from, to) ->
                kotlin.concurrent.thread {
                    go.await()
                    val t0 = System.currentTimeMillis()
                    runCatching { from.meet(to) }
                    took[i] = System.currentTimeMillis() - t0
                }
            }
            go.countDown()
            meetings.forEach { it.join() }
            assertTrue(took.max() < 8_000, "round $round: the two meetings took ${took.toList()} ms")
        }
        // One at a time, they converge as always.
        assertTrue(a.meet(b)); assertTrue(b.meet(a))
        a.sync(); b.sync()
        assertEquals(2, libraryOf(a).size)
        assertEquals(2, libraryOf(b).size)
    }

    @Test
    fun aQuickStopAndStartLeavesOneAnnouncer() {
        val a = Device(code)
        a.lan.stop(); a.lan.start()   // well inside the announcer's 5 s sleep
        Thread.sleep(6_000)           // long enough for the old one to wake
        assertEquals(1, a.lan.announcing.get(), "one device, one announcement loop")
    }

    private fun freeUdpPort() = DatagramSocket(0).use { it.localPort }
}
