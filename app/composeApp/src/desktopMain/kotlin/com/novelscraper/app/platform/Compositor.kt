package com.novelscraper.app.platform

/**
 * Putting the app's browser window somewhere it isn't in the way.
 *
 * On an ordinary desktop a window can be minimised, or placed off the side of
 * the screen. A tiling compositor does neither: it decides where every window
 * goes, has no notion of minimising, and ignores a window that asks to be put
 * somewhere. The browser the app drives then sits in the middle of the reader's
 * work for the whole session, and closing it, reasonably enough, leaves the app
 * with nothing to read sites with.
 *
 * Hyprland, which is that kind of compositor, keeps workspaces aside for exactly
 * this and takes instructions on its own socket. The app's browser is sent to
 * one of those, by the window's own address so that nothing of the reader's is
 * touched, and the workspace is brought into view when a check needs them.
 * Anywhere else this does nothing and the window is minimised as before.
 */
internal object Compositor {

    private const val TAG = "Compositor"
    private const val WORKSPACE = "novelscraper"

    @Volatile private var showing = false

    /** True on a compositor this knows how to ask. */
    val known: Boolean by lazy {
        !System.getenv("HYPRLAND_INSTANCE_SIGNATURE").isNullOrBlank() && hyprctl("version") != null
    }

    /**
     * Sends the windows of [pid] to the workspace kept aside, once they exist.
     * A window takes a moment to appear after its browser starts, so this waits
     * for one rather than asking once.
     */
    fun keepAside(pid: Long): Boolean {
        if (!known) return false
        repeat(20) {
            val windows = windowsOf(pid)
            if (windows.isNotEmpty()) {
                for (address in windows) {
                    hyprctl("dispatch", "movetoworkspacesilent", "special:$WORKSPACE,address:$address")
                }
                Log.i(TAG, "the browser window is on the workspace kept for it")
                return true
            }
            Thread.sleep(250)
        }
        return false
    }

    /** Bring that workspace into view, for a check that needs the reader. */
    fun reveal(): Boolean {
        if (!known) return false
        if (showing) return true
        if (hyprctl("dispatch", "togglespecialworkspace", WORKSPACE) == null) return false
        showing = true
        return true
    }

    /** Send it away again. */
    fun conceal(): Boolean {
        if (!known) return false
        if (!showing) return true
        if (hyprctl("dispatch", "togglespecialworkspace", WORKSPACE) == null) return false
        showing = false
        return true
    }

    /** The addresses of the windows belonging to a process. */
    private fun windowsOf(pid: Long): List<String> {
        val clients = hyprctl("clients", "-j") ?: return emptyList()
        // Two plain fields out of a list of objects, not worth a parser here.
        return Regex("\"address\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"pid\"\\s*:\\s*(\\d+)")
            .findAll(clients)
            .filter { it.groupValues[2].toLongOrNull() == pid }
            .map { it.groupValues[1] }
            .toList()
    }

    private fun hyprctl(vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("hyprctl") + args)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        if (process.exitValue() == 0) out else null
    } catch (e: Exception) {
        null
    }
}
