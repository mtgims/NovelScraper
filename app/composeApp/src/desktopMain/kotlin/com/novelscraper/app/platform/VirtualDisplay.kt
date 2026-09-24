package com.novelscraper.app.platform

import java.io.File

/**
 * A screen of the app's own, for the browser it drives.
 *
 * A site's check will not finish in a window that isn't being painted: headless
 * is refused outright, and a minimised or hidden window is a page the browser
 * reports as hidden, which a check waits on, gives up on and starts again. It
 * wants a real window, mapped and drawn, with the graphics card behind it.
 *
 * What it does not want is to be looked at. An X server with no monitor behind
 * it gives the browser exactly the window it asks for, on a screen nobody can
 * see: the reader's desktop never has a browser on it, and the graphics card is
 * still the one doing the drawing (measured: ANGLE on the real card, WebGL
 * enabled, a check passing by itself in a couple of seconds).
 *
 * Linux only, and only where Xvfb is installed. Everywhere else the window is
 * parked off the side of the screen instead.
 */
internal object VirtualDisplay {

    private const val TAG = "VirtualDisplay"

    private var process: Process? = null

    /** The display the browser should use, once there is one. */
    @Volatile
    var name: String? = null
        private set

    /** Whether a screen of our own is possible on this machine at all. */
    val possible: Boolean by lazy { Os.isLinux && xvfb != null }

    private val xvfb: File? by lazy {
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .map { File(it, "Xvfb") }
            .firstOrNull { it.canExecute() }
    }

    /** Starts one if needed and hands back its name, or null if it can't. */
    @Synchronized
    fun ensure(): String? {
        name?.let { if (process?.isAlive == true) return it }
        val server = xvfb ?: return null
        // A number nothing else is using: the sockets say which are taken.
        val number = (90..99).firstOrNull { !File("/tmp/.X11-unix/X$it").exists() } ?: return null
        val display = ":$number"
        return try {
            process = ProcessBuilder(
                server.path, display,
                // Big enough for a browser window that a check is happy with.
                "-screen", "0", "1400x900x24",
                // Nothing on the network reaches it, and it stays up between
                // browsers rather than resetting when the last one closes.
                "-nolisten", "tcp", "-noreset",
            )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val socket = File("/tmp/.X11-unix/X$number")
            val until = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < until && !socket.exists()) Thread.sleep(100)
            if (!socket.exists() || process?.isAlive != true) {
                Log.w(TAG, "Xvfb didn't come up on $display")
                stop()
                null
            } else {
                Log.i(TAG, "the browser gets a screen of its own on $display")
                name = display
                display
            }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't start a screen of our own: ${e.message}")
            process = null
            null
        }
    }

    @Synchronized
    fun stop() {
        runCatching { process?.destroy() }
        process = null
        name = null
    }

    init {
        runCatching { Runtime.getRuntime().addShutdownHook(Thread { runCatching { stop() } }) }
    }
}
