package com.novelscraper.app.tts

import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

/** The desktop's media controls: org.mpris.MediaPlayer2. */
@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

/** Its transport: what the media keys and players like playerctl call. */
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
}

/**
 * Narration as a media player on the Linux desktop (MPRIS), so the keyboard's
 * play/pause, next and previous keys work while the app is in the background,
 * and the current chapter shows in the desktop's media widget (and in playerctl).
 *
 * The bus is optional: a session without D-Bus (a bare X session, a container)
 * just doesn't get media keys, and nothing else changes.
 */
object MprisPlayer {

    private const val BUS_NAME = "org.mpris.MediaPlayer2.novelscraper"
    private const val PATH = "/org/mpris/MediaPlayer2"
    private const val PLAYER = "org.mpris.MediaPlayer2.Player"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: DBusConnection? = null

    /** Connect and publish; safe to call once at startup. */
    fun start(onRaise: () -> Unit = {}, onQuit: () -> Unit = {}) {
        // MPRIS is a Linux desktop's protocol, carried on a session bus that
        // other systems don't have. Elsewhere the media keys are somebody else's
        // to handle and this does nothing rather than failing loudly.
        if (!Os.isLinux) return
        scope.launch {
            try {
                val conn = DBusConnectionBuilder.forSessionBus().build()
                conn.requestBusName(BUS_NAME)
                conn.exportObject(PATH, Exported(onRaise, onQuit))
                connection = conn
                Log.i(TAG, "media keys ready: $BUS_NAME")
                // Keep the desktop's widget in step with what is being narrated.
                var last: TtsController.State? = null
                TtsController.state.collectLatest { s ->
                    val before = last
                    last = s
                    if (before?.playing != s.playing || before.active != s.active ||
                        before.chapterTitle != s.chapterTitle
                    ) publish(s)
                }
            } catch (e: Exception) {
                // No session bus, or another instance already owns the name.
                Log.w(TAG, "no media keys: $e")
            }
        }
    }

    fun stop() {
        runCatching { connection?.releaseBusName(BUS_NAME) }
        runCatching { connection?.disconnect() }
        connection = null
    }

    private fun publish(s: TtsController.State) {
        val conn = connection ?: return
        runCatching {
            conn.sendMessage(
                Properties.PropertiesChanged(PATH, PLAYER, properties(s), emptyList()),
            )
        }
    }

    private fun properties(s: TtsController.State): Map<String, Variant<*>> = mapOf(
        "PlaybackStatus" to Variant(
            when {
                !s.active -> "Stopped"
                s.playing -> "Playing"
                else -> "Paused"
            },
        ),
        "Metadata" to Variant(
            mapOf(
                "mpris:trackid" to Variant(DBusPath("/com/novelscraper/track/${s.bookId}/${s.position}")),
                "xesam:title" to Variant(s.chapterTitle.ifBlank { "NovelScraper" }),
                "xesam:artist" to Variant(arrayOf("NovelScraper")),
                "mpris:length" to Variant(s.totalSec.toLong() * 1_000_000L),
            ),
            "a{sv}",
        ),
        "CanPlay" to Variant(true),
        "CanPause" to Variant(true),
        "CanGoNext" to Variant(true),
        "CanGoPrevious" to Variant(true),
        "CanSeek" to Variant(false),
        "CanControl" to Variant(true),
    )

    /** The object on the bus: the media keys' commands and the properties a
     *  desktop widget reads. */
    class Exported(
        private val onRaise: () -> Unit,
        private val onQuit: () -> Unit,
    ) : MediaPlayer2, MediaPlayer2Player, Properties {

        override fun getObjectPath() = PATH
        override fun isRemote() = false

        override fun Raise() = onRaise()
        override fun Quit() = onQuit()

        override fun Next() { TtsController.nextChapter() }
        override fun Previous() { TtsController.prevChapter() }
        override fun Pause() { TtsController.pause() }
        override fun PlayPause() { TtsController.toggle() }
        override fun Stop() { TtsController.stop() }
        override fun Play() { if (!TtsController.state.value.playing) TtsController.toggle() }

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, property: String): A =
            (GetAll(interfaceName)[property]?.value ?: "") as A

        override fun <A : Any?> Set(interfaceName: String, property: String, value: A) {}

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
            PLAYER -> properties(TtsController.state.value)
            else -> mapOf(
                "Identity" to Variant("NovelScraper"),
                "DesktopEntry" to Variant("novelscraper"),
                "CanRaise" to Variant(true),
                "CanQuit" to Variant(true),
                "HasTrackList" to Variant(false),
                "SupportedUriSchemes" to Variant(arrayOf<String>()),
                "SupportedMimeTypes" to Variant(arrayOf<String>()),
            )
        }
    }

    private const val TAG = "Mpris"
}
