package com.novelscraper.app.tts

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The connection between the app and the copy narrating on the card. */
class VoiceHostTest {

    private class Fake(val failOn: String? = null) : TtsModel {
        var released = false
        override val sampleRate = 24000
        override val numSpeakers = 54
        override fun generate(text: String, speaker: Int, speed: Float): FloatArray {
            if (text == failOn) error("couldn't say that")
            return FloatArray(text.length * 10) { i -> (i % 7) / 7f * speed + speaker }
        }
        override fun release() { released = true }
    }

    /** A copy on a thread, answering with [model] on one end of a loopback pair. */
    private var appSocket: Socket? = null

    private fun pair(model: TtsModel, onServe: (Socket) -> Unit = { VoiceHost.serve(it, model) }): Pair<TtsModel, Thread> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val host = thread { server.use { onServe(it.accept()) } }
        val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort).also { appSocket = it }
        return VoiceHost.connect(socket, process = null) to host
    }

    @Test
    fun sentencesGoOverAndTheirAudioComesBack() {
        val model = Fake()
        val (voice, host) = pair(model)
        assertEquals(24000, voice.sampleRate)
        assertEquals(54, voice.numSpeakers)
        val text = "The lamps were lit early that evening — and “quoted”."
        assertContentEquals(model.generate(text, 3, 1.2f), voice.generate(text, 3, 1.2f))
        repeat(20) { voice.generate("Sentence $it.", 0, 1f) }
        voice.release()
        host.join(5_000)
        assertTrue(model.released, "the copy didn't release its model when told to quit")
    }

    /** A sentence the copy couldn't do is an ordinary failure: the copy is
     *  still there and the next sentence works. */
    @Test
    fun aSentenceThatFailsInTheCopyIsNotADeadCopy() {
        val (voice, _) = pair(Fake(failOn = "bad"))
        assertFailsWith<IllegalStateException> { voice.generate("bad", 0, 1f) }
        assertTrue(voice.generate("good", 0, 1f).isNotEmpty())
        voice.release()
    }

    /** The copy dying mid-sentence (a native abort) shows up as [VoiceHost.Died]. */
    @Test
    fun aCopyThatDiesIsReportedAsDied() {
        val (voice, host) = pair(Fake()) { s ->
            // Answers the greeting, then goes the way an aborted process does.
            val out = java.io.DataOutputStream(s.getOutputStream())
            out.writeInt(0); out.writeInt(24000); out.writeInt(54); out.flush()
            s.getInputStream().read()
            s.close()
        }
        assertFailsWith<VoiceHost.Died> { voice.generate("Anything.", 0, 1f) }
        assertFailsWith<VoiceHost.Died> { voice.generate("And after.", 0, 1f) }
        host.join(5_000)
    }

    /** The app going closes the connection, and the copy ends with it. */
    @Test
    fun theCopyEndsWhenTheAppGoes() {
        val model = Fake()
        val (voice, host) = pair(model)
        voice.generate("One.", 0, 1f)
        // No quit: the socket just closes, as when the app is killed.
        appSocket!!.close()
        host.join(5_000)
        assertTrue(!host.isAlive && model.released)
    }
}
