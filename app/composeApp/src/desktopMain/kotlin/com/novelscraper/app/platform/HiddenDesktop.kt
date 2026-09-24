package com.novelscraper.app.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * A desktop of the app's own on Windows, where the browser it drives can have a
 * real window without the reader ever seeing it: Windows' counterpart of the
 * screen [VirtualDisplay] gives the browser on Linux.
 *
 * A Windows session can hold several desktops, and only one of them is the one
 * on the monitor. A window on another is a whole, ordinary window, painted and
 * reported as visible to the page inside it, which is what a site's check wants,
 * yet it appears nowhere: not on the screen, not in the taskbar, not in Alt+Tab.
 * Parking a window off the edge of the screen hid the window but not its taskbar
 * button, and a minimised one is not painted and fails its checks.
 */
object HiddenDesktop {

    private const val TAG = "HiddenDesktop"
    private const val NAME = "NovelScraperBrowser"

    /** Held open for the whole run: a desktop goes when its last handle does. */
    @Volatile private var handle: Pointer? = null

    val possible: Boolean get() = Os.isWindows

    /**
     * Starts [command] with its windows on the app's desktop, and hands back the
     * process, or null if that couldn't be done (the caller then starts it the
     * ordinary way).
     */
    fun launch(command: List<String>): ProcessHandle? {
        if (!possible) return null
        return runCatching {
            ensureDesktop() ?: return null
            val startup = WinBase.STARTUPINFO().apply { lpDesktop = NAME }
            val info = WinBase.PROCESS_INFORMATION()
            val ok = Kernel32.INSTANCE.CreateProcess(
                null, commandLine(command), null, null, false,
                DWORD(CREATE_NO_WINDOW.toLong()), null, null, startup, info,
            )
            if (!ok) {
                Log.w(TAG, "couldn't start the browser on its own desktop (error ${Kernel32.INSTANCE.GetLastError()})")
                return null
            }
            val pid = info.dwProcessId.toLong()
            Kernel32.INSTANCE.CloseHandle(info.hThread)
            Kernel32.INSTANCE.CloseHandle(info.hProcess)
            ProcessHandle.of(pid).orElse(null)
        }.getOrElse {
            Log.w(TAG, "couldn't start the browser on its own desktop: ${it.message}")
            null
        }
    }

    @Synchronized
    private fun ensureDesktop(): Pointer? {
        handle?.let { return it }
        val made = User32Desktop.CreateDesktop(NAME, null, null, 0, GENERIC_ALL, null)
        if (made == null) {
            Log.w(TAG, "couldn't make a desktop (error ${Kernel32.INSTANCE.GetLastError()})")
            return null
        }
        Log.i(TAG, "the browser has a desktop of its own")
        handle = made
        return made
    }

    /** One command line, quoted the way Windows programs read theirs back. */
    private fun commandLine(args: List<String>): String = args.joinToString(" ") { arg ->
        if (arg.isNotEmpty() && arg.none { it == ' ' || it == '\t' || it == '"' }) arg
        else buildString {
            append('"')
            var slashes = 0
            for (c in arg) {
                when (c) {
                    '\\' -> slashes++
                    '"' -> { repeat(slashes * 2 + 1) { append('\\') }; slashes = 0; append('"'); continue }
                    else -> { repeat(slashes) { append('\\') }; slashes = 0 }
                }
                if (c != '\\') append(c)
            }
            repeat(slashes * 2) { append('\\') }
            append('"')
        }
    }

    @Suppress("FunctionName")
    private interface User32Api : StdCallLibrary {
        fun CreateDesktop(
            name: String, device: String?, devMode: Pointer?, flags: Int, access: Int, attributes: Pointer?,
        ): Pointer?
    }

    private val User32Desktop: User32Api by lazy {
        Native.load("user32", User32Api::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    private const val GENERIC_ALL = 0x10000000
    private const val CREATE_NO_WINDOW = 0x08000000
}
