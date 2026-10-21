package com.novelscraper.app.sync

import com.novelscraper.app.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Which files go between two devices' sync folders, and which can go away.
 * Nothing here touches the network, so it can be checked on its own.
 *
 * Only batch files travel ([SyncFiles]); the manifest is each device's own. A
 * batch older than its device's newest snapshot is covered by that snapshot,
 * so it is neither copied nor kept. Two copies of one name that differ (an
 * upload rewritten after a crash) are settled by the device that wrote it: its
 * copy wins.
 */
internal object Replication {

    data class Entry(val name: String, val size: Long, val sha: String)

    /** Each device's newest snapshot, across every listing given. */
    fun floors(vararg listings: Collection<Entry>): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (l in listings) for (e in l) {
            val b = SyncFiles.parse(e.name) ?: continue
            if (b.snapshot && b.seq > (out[b.device] ?: -1)) out[b.device] = b.seq
        }
        return out
    }

    fun stale(name: String, floors: Map<String, Long>): Boolean {
        val b = SyncFiles.parse(name) ?: return true
        return b.seq < (floors[b.device] ?: 0)
    }

    /**
     * What [receiver] should take from [sender]'s files. [receiverDevice] takes
     * back none of the batches it wrote: what it no longer has it removed on
     * purpose. Only a batch under its id numbered past all of its own can't be
     * one it wrote; that is another copy of its database writing as it, and
     * [FolderSync] has to see it to tell.
     */
    fun wanted(
        receiver: Map<String, Entry>,
        receiverDevice: String,
        sender: Collection<Entry>,
        senderDevice: String,
        floors: Map<String, Long>,
    ): List<Entry> {
        val newestOwn = newest(receiver.keys, receiverDevice)
        return sender.filter { e -> accepts(receiver[e.name], receiverDevice, newestOwn, e, senderDevice, floors) }
    }

    /** Whether a receiver holding [mine] (or nothing) under [e]'s name takes [e]. */
    fun accepts(
        mine: Entry?,
        receiverDevice: String,
        newestOwn: Long,
        e: Entry,
        senderDevice: String,
        floors: Map<String, Long>,
    ): Boolean {
        val b = SyncFiles.parse(e.name) ?: return false
        if (stale(e.name, floors)) return false
        if (b.device == receiverDevice) return mine == null && b.seq > newestOwn
        return mine == null || (mine.sha != e.sha && b.device == senderDevice)
    }

    /** The highest batch number of [device] among [names]. */
    fun newest(names: Collection<String>, device: String): Long =
        names.mapNotNull { SyncFiles.parse(it) }.filter { it.device == device }.maxOfOrNull { it.seq } ?: 0

    /** This device's copies of other devices' batches that a snapshot now covers. */
    fun prunable(local: Collection<Entry>, me: String, floors: Map<String, Long>): List<String> =
        local.filter { e -> SyncFiles.parse(e.name)?.let { it.device != me && stale(e.name, floors) } == true }
            .map { it.name }
}

/**
 * Same-Wi-Fi sync: the devices that share a sync code find each other on the
 * local network and copy each other's sync files, with no server and nothing
 * leaving the network. Apart, each keeps working on its own; when they meet
 * again the newer change to anything wins, as it always does ([FolderSync]).
 * A change also travels through a third device: whatever one device has, the
 * next one it meets gets.
 *
 * - **Finding each other.** Every few seconds a UDP broadcast on [discoveryPort]
 *   says "a device of this library is here, at this port". It names the library
 *   by a tag made from the code that changes every ten minutes, so it says
 *   nothing to anyone without the code and can't be used to follow a device.
 *   Where a network keeps devices from hearing each other, the other device's
 *   address can be given by hand ([manualPeers]).
 * - **Trusting each other.** A connection starts with each side proving, over
 *   the other's random nonce, that it holds the code. Nothing is listed or sent
 *   before both have.
 * - **Copying.** Each side lists its files with their hashes, and fetches and
 *   sends what [Replication] says. The files themselves were sealed with the
 *   code's key before they were ever written, so even a device that answered
 *   the proof could not read or alter them without the code.
 *
 * [onReceived] is called after a meeting brought something new, so the library
 * can apply it.
 */
class LanSync internal constructor(
    private val folder: LocalFolder,
    private val keys: PairKeys,
    @Volatile var device: String,
    private val name: String,
    private val onReceived: () -> Unit,
    private val discoveryPort: Int = DISCOVERY_PORT,
    private val serverPort: Int = SERVER_PORT,
    /** Where announcements go besides the network's broadcast addresses (tests). */
    private val extraTargets: List<InetSocketAddress> = emptyList(),
    private val manualPeers: () -> List<String> = { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A device met: [address] as host:port, [lastSync] when they last swapped files (0: not yet). */
    data class Peer(val device: String, val name: String, val address: String, val lastSeen: Long, val lastSync: Long)

    /** [addresses]: this device's own, as host:port, for typing into another. */
    data class State(val running: Boolean = false, val addresses: List<String> = emptyList(), val peers: List<Peer> = emptyList())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val peers = ConcurrentHashMap<String, Peer>()
    /**
     * Held for a whole meeting, so two never read and write the folder at once.
     * Never waited for while the other device might be waiting in turn: the
     * side that connects takes it before connecting (holding nothing of the
     * other's yet), and the side connected to only takes it if it is free,
     * answering "busy" otherwise. Both used to wait, and two devices meeting
     * at once each held their own while waiting for the other's, until both
     * gave up on the read timeout.
     */
    private val meeting = java.util.concurrent.locks.ReentrantLock()

    /** Devices an exchange is under way with, so an announcement doesn't start another. */
    private val exchanging = ConcurrentHashMap.newKeySet<String>()

    /** Bumped by every [start]: an announce loop from before a quick stop and start ends. */
    @Volatile private var generation = 0

    /** Announce loops running now (tests). */
    internal val announcing = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var udp: DatagramSocket? = null
    @Volatile private var running = false

    /** The port the server got: [serverPort] if it was free, any other if not. */
    val port: Int get() = server?.localPort ?: 0

    // --- running ---------------------------------------------------------------------

    @Synchronized
    fun start() {
        if (running) return
        running = true
        server = runCatching { ServerSocket(serverPort) }.recoverCatching { ServerSocket(0) }
            .onFailure { Log.w(TAG, "no server: ${it.message}") }.getOrNull()
        server?.let { s -> daemon("lan-server") { serve(s) } }
        udp = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(discoveryPort))
            }
        }.onFailure { Log.w(TAG, "not listening for other devices: ${it.message}") }.getOrNull()
        udp?.let { u -> daemon("lan-listen") { listen(u) } }
        val gen = ++generation
        daemon("lan-announce") { announceLoop(gen) }
        publish()
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        runCatching { udp?.close() }
        server = null
        udp = null
        publish()
    }

    /** Swap files with every device met lately, and those given by address. */
    suspend fun exchangeAll(): Unit = withContext(Dispatchers.IO) {
        val recent = peers.values.filter { now() - it.lastSeen < PEER_TTL_MS }.map { it.address }
        for (address in (recent + manualPeers()).distinct()) {
            val (host, port) = splitAddress(address) ?: continue
            runCatching { exchange(host, port) }.onFailure { Log.d(TAG, "no exchange with $address: ${it.message}") }
        }
    }

    // --- finding each other ------------------------------------------------------------

    private fun window() = now() / TAG_WINDOW_MS

    private fun announceLoop(gen: Int) {
        announcing.incrementAndGet()
        val socket = DatagramSocket().apply { broadcast = true }
        try { socket.use {
            while (running && gen == generation) {
                val port = port
                if (port > 0) {
                    val msg = buildJsonObject {
                        put("app", "novelscraper"); put("v", 1)
                        put("tag", keys.tag(window())); put("device", device)
                        put("port", port); put("name", name)
                    }.toString().toByteArray()
                    for (target in broadcastTargets()) {
                        runCatching { socket.send(DatagramPacket(msg, msg.size, target)) }
                    }
                }
                Thread.sleep(ANNOUNCE_MS)
            }
        } } finally {
            announcing.decrementAndGet()
        }
    }

    private fun broadcastTargets(): List<InetSocketAddress> {
        val out = ArrayList<InetSocketAddress>(extraTargets)
        out += InetSocketAddress(InetAddress.getByName("255.255.255.255"), discoveryPort)
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.interfaceAddresses) a.broadcast?.let { out += InetSocketAddress(it, discoveryPort) }
            }
        }
        return out
    }

    private fun listen(socket: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running) {
            val packet = DatagramPacket(buf, buf.size)
            try { socket.receive(packet) } catch (_: SocketException) { break } catch (_: IOException) { continue }
            val msg = runCatching {
                Json.parseToJsonElement(String(packet.data, 0, packet.length)).jsonObject
            }.getOrNull() ?: continue
            if (msg.str("app") != "novelscraper") continue
            val tag = msg.str("tag")
            val w = window()
            if (tag != keys.tag(w) && tag != keys.tag(w - 1)) continue
            val other = msg.str("device") ?: continue
            if (other == device) continue
            val port = msg["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
            val address = "${packet.address.hostAddress}:$port"
            val known = peers[other]
            peers[other] = Peer(other, msg.str("name") ?: "Another device", address, now(), known?.lastSync ?: 0)
            publish()
            // Newly met, or not swapped with for a while: do it now, unless a
            // meeting with it is already under way. A moment's random wait
            // first: the other device has usually just heard this one too, and
            // two meetings started at the same instant both come back "busy".
            if ((known == null || now() - known.lastSync > RESYNC_MS) && exchanging.add(other)) {
                val host = packet.address.hostAddress
                daemon("lan-exchange") {
                    try {
                        Thread.sleep(kotlin.random.Random.nextLong(JITTER_MS))
                        runCatching { exchange(host, port) }
                    } finally {
                        exchanging.remove(other)
                    }
                }
            }
        }
    }

    // --- one meeting: the side that connected --------------------------------------------

    /** Connect to another device and swap files. False if it isn't there, isn't
     *  ours, or is busy with another meeting (try again later). */
    internal fun exchange(host: String, port: Int): Boolean {
        meeting.lock()
        try {
            return exchangeLocked(host, port)
        } finally {
            meeting.unlock()
        }
    }

    private fun exchangeLocked(host: String, port: Int): Boolean {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), CONNECT_MS)
            s.soTimeout = READ_MS
            val io = Frames(s)
            val nonce = nonce()
            io.send(buildJsonObject {
                put("t", "hello"); put("v", 1); put("device", device); put("name", name); put("nonce", nonce)
                put("port", this@LanSync.port)
            })
            val hello = io.receive()
            val other = hello.str("device") ?: return false
            if (hello.str("proof") != keys.proof("server", nonce, other)) {
                Log.w(TAG, "$host:$port doesn't hold this library's code")
                return false
            }
            io.send(buildJsonObject { put("t", "proof"); put("proof", keys.proof("client", hello.str("nonce").orEmpty(), device)) })
            if (io.receive().str("t") != "ok") return false

            var received = false
            io.send(buildJsonObject { put("t", "list") })
            val theirs = entries(io.receive()["files"]?.jsonArray)
            val mine = localEntries()
            val floors = Replication.floors(mine.values, theirs)
            for (e in Replication.wanted(mine, device, theirs, other, floors)) {
                io.send(buildJsonObject { put("t", "get"); put("name", e.name) })
                val reply = io.receive()
                if (reply.str("t") != "file") continue
                val bytes = io.receiveBytes()
                if (sha(bytes) != e.sha) continue
                folder.writeNow(e.name, bytes)
                received = true
            }
            val theirsByName = theirs.associateBy { it.name }
            for (e in Replication.wanted(theirsByName, other, mine.values, device, floors)) {
                val bytes = folder.readNow(e.name) ?: continue
                io.send(buildJsonObject { put("t", "put"); put("name", e.name) })
                io.sendBytes(bytes)
                io.receive()
            }
            io.send(buildJsonObject { put("t", "bye") })
            // The other side has finished with what it took when it says so.
            io.receive()
            prune(floors)
            met(other, hello.str("name"), "$host:$port")
            if (received) onReceived()
            return true
        }
    }

    // --- one meeting: the side that was connected to --------------------------------------

    private fun serve(server: ServerSocket) {
        while (running) {
            val s = try { server.accept() } catch (_: IOException) { break }
            daemon("lan-session") { s.use { runCatching { session(it) }.onFailure { e -> Log.d(TAG, "session: ${e.message}") } } }
        }
    }

    private fun session(s: Socket) {
        s.soTimeout = READ_MS
        val io = Frames(s)
        val hello = io.receive()
        if (hello.str("t") != "hello") return
        val other = hello.str("device") ?: return
        val nonce = nonce()
        io.send(buildJsonObject {
            put("t", "hello"); put("v", 1); put("device", device); put("name", name); put("nonce", nonce)
            put("proof", keys.proof("server", hello.str("nonce").orEmpty(), device))
        })
        // The other side proves itself before anything is listed or taken.
        if (io.receive().str("proof") != keys.proof("client", nonce, other)) return
        // Busy with another meeting: say so rather than wait, since that one
        // may be waiting on this device's own (see [meeting]).
        if (!meeting.tryLock()) {
            io.send(buildJsonObject { put("t", "busy") })
            return
        }
        var received = false
        try {
            io.send(buildJsonObject { put("t", "ok") })
            var floors = Replication.floors(localEntries().values)
            while (true) {
                val req = io.receive()
                when (req.str("t")) {
                    "list" -> {
                        val mine = localEntries()
                        floors = Replication.floors(mine.values)
                        io.send(buildJsonObject { put("t", "list"); put("files", toJson(mine.values)) })
                    }
                    "get" -> {
                        val n = req.str("name").orEmpty()
                        val bytes = if (SyncFiles.parse(n) != null) folder.readNow(n) else null
                        if (bytes == null) io.send(buildJsonObject { put("t", "missing") })
                        else { io.send(buildJsonObject { put("t", "file") }); io.sendBytes(bytes) }
                    }
                    "put" -> {
                        val n = req.str("name").orEmpty()
                        val bytes = io.receiveBytes()
                        // The same rule the side that connected follows (Replication.accepts).
                        val local = localEntries()
                        val take = Replication.accepts(
                            local[n], device, Replication.newest(local.keys, device),
                            Replication.Entry(n, bytes.size.toLong(), sha(bytes)), other, floors,
                        )
                        if (take) { folder.writeNow(n, bytes); received = true }
                        io.send(buildJsonObject { put("t", "ok") })
                    }
                    else -> {
                        prune(Replication.floors(localEntries().values))
                        io.send(buildJsonObject { put("t", "bye") })
                        break
                    }
                }
            }
        } finally {
            meeting.unlock()
        }
        // Where the other device can be reached in turn: its address, and the
        // port its own server said it has.
        val back = hello["port"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }
            ?.let { "${s.inetAddress.hostAddress}:$it" }
        met(other, hello.str("name"), back)
        if (received) onReceived()
    }

    // --- bookkeeping -----------------------------------------------------------------------

    private fun localEntries(): Map<String, Replication.Entry> =
        folder.listNow().filter { SyncFiles.parse(it.name) != null }.mapNotNull { f ->
            folder.readNow(f.name)?.let { Replication.Entry(f.name, it.size.toLong(), sha(it)) }
        }.associateBy { it.name }

    private fun prune(floors: Map<String, Long>) {
        for (n in Replication.prunable(localEntries().values, device, floors)) folder.deleteNow(n)
    }

    private fun met(other: String, otherName: String?, address: String?) {
        val known = peers[other]
        peers[other] = Peer(
            other, otherName ?: known?.name ?: "Another device",
            address ?: known?.address ?: "", now(), now(),
        )
        publish()
    }

    private fun publish() {
        _state.value = State(
            running = running,
            addresses = if (running && port > 0) ownAddresses().map { "$it:$port" } else emptyList(),
            peers = peers.values.sortedByDescending { it.lastSync },
        )
    }

    private fun entries(a: JsonArray?): List<Replication.Entry> = a.orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val n = o.str("name") ?: return@mapNotNull null
        Replication.Entry(n, o["size"]?.jsonPrimitive?.long ?: 0, o.str("sha").orEmpty())
    }

    private fun toJson(es: Collection<Replication.Entry>) = buildJsonArray {
        for (e in es) add(buildJsonObject { put("name", e.name); put("size", e.size); put("sha", e.sha) })
    }

    /** Length-prefixed frames: JSON for talk, raw bytes for files. */
    private class Frames(s: Socket) {
        private val input = DataInputStream(s.getInputStream().buffered())
        private val output = DataOutputStream(s.getOutputStream().buffered())

        fun send(o: JsonObject) = sendBytes(o.toString().toByteArray())

        fun sendBytes(b: ByteArray) {
            output.writeInt(b.size); output.write(b); output.flush()
        }

        fun receive(): JsonObject = Json.parseToJsonElement(String(receiveBytes())).jsonObject

        fun receiveBytes(): ByteArray {
            val n = input.readInt()
            if (n < 0 || n > MAX_FRAME) throw IOException("frame of $n bytes")
            return ByteArray(n).also(input::readFully)
        }
    }

    companion object {
        private const val TAG = "LanSync"
        const val DISCOVERY_PORT = 47811
        const val SERVER_PORT = 47812
        private const val ANNOUNCE_MS = 5_000L
        private const val TAG_WINDOW_MS = 10 * 60_000L
        private const val PEER_TTL_MS = 2 * 60_000L
        private const val RESYNC_MS = 30_000L
        private const val JITTER_MS = 2_000L
        private const val CONNECT_MS = 3_000
        private const val READ_MS = 20_000
        private const val MAX_FRAME = 17 * 1024 * 1024
        private val random = SecureRandom()

        private fun nonce() = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        internal fun sha(b: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** "host:port", with IPv6 in brackets; null if it isn't one. */
        fun splitAddress(address: String): Pair<String, Int>? {
            val a = address.trim()
            val i = a.lastIndexOf(':')
            if (i <= 0) return null
            val host = a.substring(0, i).removePrefix("[").removeSuffix("]")
            val port = a.substring(i + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return host to port
        }

        /** This device's addresses on its local networks. */
        fun ownAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())

        private fun daemon(name: String, block: () -> Unit) =
            Thread(block, name).apply { isDaemon = true }.start()
    }
}
