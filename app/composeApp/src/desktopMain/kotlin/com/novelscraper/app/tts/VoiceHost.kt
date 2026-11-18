package com.novelscraper.app.tts

import com.novelscraper.app.platform.DesktopDirs
import com.novelscraper.app.platform.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Narration on the graphics card, in a copy of the app of its own.
 *
 * A failure on the card can end the process instead of throwing: ONNX
 * Runtime's out-of-memory error escapes sherpa-onnx's native code and aborts,
 * as it did on 2026-11-17 after 37 minutes of narration, taking the library
 * window with it. So the card's libraries are only ever loaded in this copy,
 * and the app talks to it over a connection on the loopback address: a
 * sentence goes over, its audio comes back. If the copy dies, the app is
 * still there and narration carries on from the processor ([Died]).
 *
 * Standard output is no good for this: the native library prints its
 * configuration there. The copy's output goes to `gpu-voice.log` instead.
 * It exits when the connection closes, so it never outlives the app.
 */
object VoiceHost {
    private const val TAG = "VoiceHost"
    const val ARG = "--voice-host"

    private const val OP_GENERATE = 1
    private const val OP_QUIT = 2
    private const val OK = 0
    private const val FAILED = 1

    /** The copy stopped answering: it died, hung, or the connection broke. */
    class Died(message: String, cause: Throwable? = null) : IOException(message, cause)

    // --- in the app ----------------------------------------------------------------

    /** How long the copy may take to load the card's libraries and the voice. */
    internal var startTimeoutMs = 120_000L
    /** How long one sentence may take before the copy is given up on. */
    internal var sentenceTimeoutMs = 90_000L

    /** The copy started last, for tests that kill it. */
    @Volatile internal var running: Process? = null

    /**
     * Starts the copy with [command] (the app's launcher, or `java -cp`) and
     * connects to it. [arguments] are what it needs to build the voice. The
     * returned voice's [TtsModel.generate] throws [Died] if the copy goes.
     */
    fun start(command: List<String>, arguments: List<String>, adapter: Int, log: File): TtsModel {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        server.soTimeout = startTimeoutMs.toInt()
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val process = GpuVoice.probeProcess(
            command + listOf(ARG, server.localPort.toString(), token) + arguments, adapter, log,
        ).directory(DesktopDirs.cache).start()
        Log.i(TAG, "started the graphics card's voice in a separate process (pid ${process.pid()})")
        running = process
        val socket = try {
            server.use { s ->
                // Anything else on this machine could connect: only the copy knows the token.
                generateSequence { s.accept() }.first { c ->
                    c.soTimeout = startTimeoutMs.toInt()
                    val ok = runCatching { DataInputStream(c.getInputStream()).readUTF() == token }.getOrDefault(false)
                    if (!ok) c.close()
                    ok
                }
            }
        } catch (e: Exception) {
            process.destroyForcibly()
            throw Died("the card's voice didn't start: ${tail(log)}", e)
        }
        return try {
            connect(socket, process)
        } catch (e: Exception) {
            process.destroyForcibly()
            throw Died("the card's voice didn't load: ${e.message ?: tail(log)}", e)
        }
    }

    /** The app's side of a connection to a copy (also what tests use). */
    internal fun connect(socket: Socket, process: Process?): TtsModel {
        socket.soTimeout = startTimeoutMs.toInt()
        socket.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        if (input.readInt() != OK) throw IOException(input.readUTF())
        val rate = input.readInt()
        val speakers = input.readInt()
        socket.soTimeout = sentenceTimeoutMs.toInt()
        return object : TtsModel {
            override val sampleRate = rate
            override val numSpeakers = speakers
            @Volatile private var dead: Died? = null

            override fun generate(text: String, speaker: Int, speed: Float): FloatArray {
                dead?.let { throw it }
                try {
                    output.writeByte(OP_GENERATE)
                    output.writeUTF(text.take(MAX_TEXT))
                    output.writeInt(speaker)
                    output.writeFloat(speed)
                    output.flush()
                    when (input.readInt()) {
                        OK -> {
                            val n = input.readInt()
                            val bytes = ByteArray(n * 4)
                            input.readFully(bytes)
                            val out = FloatArray(n)
                            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
                            return out
                        }
                        // The copy is still there and said why (a sentence it couldn't do).
                        else -> throw IllegalStateException(input.readUTF())
                    }
                } catch (e: IOException) {
                    val exit = process?.let { p -> if (p.waitFor(2, TimeUnit.SECONDS)) " (exit ${p.exitValue()})" else "" } ?: ""
                    process?.destroyForcibly()
                    runCatching { socket.close() }
                    throw Died("the card's voice stopped answering$exit: ${e.message}", e).also { dead = it }
                }
            }

            override fun release() {
                runCatching { output.writeByte(OP_QUIT); output.flush() }
                runCatching { socket.close() }
                process?.let { p -> if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }
            }
        }
    }

    /** A sentence is never this long; a runaway one isn't sent whole. */
    private const val MAX_TEXT = 8_000

    private fun tail(log: File): String =
        runCatching { log.readLines().filter { it.isNotBlank() && !it.startsWith("OfflineTts") }.takeLast(3).joinToString(" | ") }
            .getOrDefault("").take(400)

    // --- in the copy ---------------------------------------------------------------

    /**
     * The copy's main: connects back on the port in [args], builds the voice
     * with [build], and answers until the app goes. Every native call happens
     * on this one thread (see KokoroEngine's worker for why that matters).
     */
    fun run(args: List<String>, build: (List<String>) -> TtsModel): Int {
        val (port, token) = args.takeIf { it.size >= 2 } ?: return 5
        val socket = runCatching { Socket(InetAddress.getLoopbackAddress(), port.toInt()) }.getOrElse { return 6 }
        socket.tcpNoDelay = true
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        output.writeUTF(token); output.flush()
        val model = try {
            build(args.drop(2))
        } catch (t: Throwable) {
            System.err.println("couldn't build the voice: ${t.message}")
            output.writeInt(FAILED); output.writeUTF(t.message ?: t.toString()); output.flush()
            socket.close()
            return 3
        }
        serve(socket, model, output)
        return 0
    }

    /** Answers requests on [socket] with [model] until told to quit or the
     *  connection closes (the app went: exit with it). */
    internal fun serve(socket: Socket, model: TtsModel, out: DataOutputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        out.writeInt(OK); out.writeInt(model.sampleRate); out.writeInt(model.numSpeakers); out.flush()
        try {
            while (true) {
                when (input.readByte().toInt()) {
                    OP_GENERATE -> {
                        val text = input.readUTF()
                        val speaker = input.readInt()
                        val speed = input.readFloat()
                        val pcm = try {
                            model.generate(text, speaker, speed)
                        } catch (t: Throwable) {
                            out.writeInt(FAILED); out.writeUTF(t.message ?: t.toString()); out.flush()
                            continue
                        }
                        val bytes = ByteBuffer.allocate(pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        bytes.asFloatBuffer().put(pcm)
                        out.writeInt(OK); out.writeInt(pcm.size); out.write(bytes.array()); out.flush()
                    }
                    else -> break
                }
            }
        } catch (_: IOException) {
            // The app closed the connection, or went.
        } finally {
            runCatching { model.release() }
            runCatching { socket.close() }
        }
    }
}
