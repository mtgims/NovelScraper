package com.novelscraper.app.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.sun.jna.Callback
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * The main window's frame on Windows: a real Windows frame with the app's own
 * title bar drawn where the system's would be.
 *
 * A window Java is told is undecorated has no frame at all as far as Windows is
 * concerned, and with the frame go all the things a frame brings: it cannot be
 * dragged or snapped by its title, it vanishes when minimised instead of
 * shrinking into the taskbar, it jumps rather than animates when maximised, and
 * its edges cannot be taken hold of. So the frame is given back here, and only
 * its title bar is taken away, the way browsers and Windows Terminal do it:
 *
 * - the frame's styles are put back on the window;
 * - the title bar's height is handed to the app's content when Windows measures
 *   the window (WM_NCCALCSIZE), so the app draws there itself, while the side
 *   and bottom edges stay the system's own, invisible and grabbable;
 * - when Windows asks what lies under the mouse (WM_NCHITTEST), the app's title
 *   bar answers "title bar", apart from its buttons, and a thin strip along the
 *   top answers "top edge".
 *
 * Everything else, dragging, snapping, double-clicking to maximise, the window
 * menu and the animations, is then Windows doing what it does for any window.
 */
object WindowsFrame {

    /** The title bar's height and the width of its buttons, in window pixels, as
     *  the app last laid them out. Everything above [captionHeight] and left of
     *  the buttons is somewhere to drag the window by. */
    @Volatile var captionHeight = 0
    @Volatile var buttonsWidth = 0

    @Volatile private var hwnd: HWND? = null

    /** True once the window has its frame back (the title bar redraws on it). */
    var installed by androidx.compose.runtime.mutableStateOf(false)
        private set

    private var parentPrev: Pointer? = null
    private val childPrev = HashMap<Pointer, Pointer>()

    // Held for as long as the window lives: Windows calls these, and a callback
    // the garbage collector has taken is a crash.
    private val parentProc = object : WndProc {
        override fun callback(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT =
            runCatching { parent(hWnd, msg, wParam, lParam) }
                .getOrElse { forward(parentPrev, hWnd, msg, wParam, lParam) }
    }
    private val childProc = object : WndProc {
        override fun callback(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT =
            runCatching { child(hWnd, msg, wParam, lParam) }
                .getOrElse { forward(childPrev[hWnd.pointer], hWnd, msg, wParam, lParam) }
    }

    /**
     * Gives [window] its frame back. Called once it has a native window; does
     * nothing anywhere but Windows, or if the calls aren't available.
     */
    fun install(window: java.awt.Window): Boolean {
        if (!Os.isWindows || hwnd != null) return hwnd != null
        return runCatching {
            val handle = HWND(Native.getWindowPointer(window) ?: return false)
            parentPrev = Win32.SetWindowLongPtr(handle, GWLP_WNDPROC, parentProc)
            hwnd = handle
            val style = User32.INSTANCE.GetWindowLong(handle, WinUser.GWL_STYLE)
            User32.INSTANCE.SetWindowLong(handle, WinUser.GWL_STYLE, style or FRAME_STYLES)
            // Measure the window again, which is where the title bar is taken off.
            User32.INSTANCE.SetWindowPos(
                handle, null, 0, 0, 0, 0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED,
            )
            adoptChildren(handle)
            installed = true
            true
        }.getOrElse {
            Log.w("WindowsFrame", "couldn't give the window its frame: ${it.message}")
            false
        }
    }

    /** Minimise, maximise or restore the way the system's own buttons do, so
     *  the window animates as any other would. */
    fun minimize(): Boolean = command(SC_MINIMIZE)
    fun toggleMaximized(): Boolean {
        val h = hwnd ?: return false
        return command(if (Win32.IsZoomed(h)) SC_RESTORE else SC_MAXIMIZE)
    }

    private fun command(sc: Int): Boolean {
        val h = hwnd ?: return false
        User32.INSTANCE.PostMessage(h, WM_SYSCOMMAND, WPARAM(sc.toLong()), LPARAM(0))
        return true
    }

    private fun parent(h: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT = when (msg) {
        WM_NCCALCSIZE -> {
            if (wParam.toLong() == 0L) forward(parentPrev, h, msg, wParam, lParam)
            else {
                // Let the system work out its frame, then give the top of it,
                // where its title bar would have gone, back to the window. A
                // maximised window hangs its frame over the edges of the screen,
                // so there the top is brought back down by that much.
                val params = Pointer(lParam.toLong())
                val top = params.getInt(4)
                val result = forward(parentPrev, h, msg, wParam, lParam)
                params.setInt(4, if (Win32.IsZoomed(h)) top + frameThickness(h) else top)
                result
            }
        }
        WM_NCHITTEST -> zone(h, lParam)?.let { LRESULT(it.toLong()) }
            ?: forward(parentPrev, h, msg, wParam, lParam)
        WM_STYLECHANGING -> {
            // Java adjusts the window's styles now and then, believing it has no
            // frame; the frame's own stay put whatever it asks for.
            if (wParam.toLong().toInt() == WinUser.GWL_STYLE) {
                val change = Pointer(lParam.toLong())
                change.setInt(4, change.getInt(4) or FRAME_STYLES)
            }
            forward(parentPrev, h, msg, wParam, lParam)
        }
        WM_SIZE -> forward(parentPrev, h, msg, wParam, lParam).also { adoptChildren(h) }
        else -> forward(parentPrev, h, msg, wParam, lParam)
    }

    /** The app's content is a window of its own inside the frame. Over the title
     *  bar it steps aside, so the question goes to the frame behind it. */
    private fun child(h: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
        val prev = childPrev[h.pointer]
        if (msg == WM_NCHITTEST) {
            val frame = hwnd
            if (frame != null && zone(frame, lParam) != null) return LRESULT(HTTRANSPARENT.toLong())
        }
        return forward(prev, h, msg, wParam, lParam)
    }

    /** What the frame is at this point of the screen, or null where it is the
     *  app's own (its content, and the title bar's buttons). */
    private fun zone(frame: HWND, lParam: LPARAM): Int? {
        val packed = lParam.toLong()
        val point = POINT((packed and 0xFFFF).toShort().toInt(), ((packed shr 16) and 0xFFFF).toShort().toInt())
        Win32.ScreenToClient(frame, point)
        if (point.y < 0) return null
        val client = RECT().also { User32.INSTANCE.GetClientRect(frame, it) }
        val width = client.right - client.left
        if (!Win32.IsZoomed(frame) && point.y < edgeThickness(frame)) {
            val corner = edgeThickness(frame) * 2
            return when {
                point.x < corner -> HTTOPLEFT
                point.x >= width - corner -> HTTOPRIGHT
                else -> HTTOP
            }
        }
        if (point.y < captionHeight && point.x < width - buttonsWidth) return HTCAPTION
        return null
    }

    private fun adoptChildren(frame: HWND) {
        User32.INSTANCE.EnumChildWindows(frame, { child, _ ->
            if (!childPrev.containsKey(child.pointer)) {
                Win32.SetWindowLongPtr(child, GWLP_WNDPROC, childProc)?.let { childPrev[child.pointer] = it }
            }
            true
        }, null)
    }

    private fun forward(prev: Pointer?, h: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT =
        if (prev != null) Win32.CallWindowProc(prev, h, msg, wParam, lParam)
        else User32.INSTANCE.DefWindowProc(h, msg, wParam, lParam)

    /** How far a maximised window's frame hangs over the screen's edge. */
    private fun frameThickness(h: HWND): Int {
        val dpi = Win32.GetDpiForWindow(h)
        return Win32.GetSystemMetricsForDpi(SM_CYFRAME, dpi) + Win32.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi)
    }

    /** The strip along the top that resizes the window rather than moving it. */
    private fun edgeThickness(h: HWND): Int =
        Win32.GetSystemMetricsForDpi(SM_CYFRAME, Win32.GetDpiForWindow(h)).coerceAtLeast(4)

    private interface WndProc : Callback {
        fun callback(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
    }

    @Suppress("FunctionName")
    private interface Win32Api : StdCallLibrary {
        fun SetWindowLongPtr(hWnd: HWND, index: Int, proc: WndProc): Pointer?
        fun CallWindowProc(prev: Pointer, hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
        fun ScreenToClient(hWnd: HWND, point: POINT): Boolean
        fun IsZoomed(hWnd: HWND): Boolean
        fun GetDpiForWindow(hWnd: HWND): Int
        fun GetSystemMetricsForDpi(index: Int, dpi: Int): Int
    }

    private val Win32: Win32Api by lazy {
        Native.load("user32", Win32Api::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    private const val GWLP_WNDPROC = -4
    private const val WM_SIZE = 0x0005
    private const val WM_STYLECHANGING = 0x007C
    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCHITTEST = 0x0084
    private const val WM_SYSCOMMAND = 0x0112
    private const val SC_MINIMIZE = 0xF020
    private const val SC_MAXIMIZE = 0xF030
    private const val SC_RESTORE = 0xF120
    private const val HTTRANSPARENT = -1
    private const val HTCAPTION = 2
    private const val HTTOP = 12
    private const val HTTOPLEFT = 13
    private const val HTTOPRIGHT = 14
    private const val SM_CYFRAME = 33
    private const val SM_CXPADDEDBORDER = 92
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    /** WS_CAPTION, WS_THICKFRAME, WS_SYSMENU, WS_MINIMIZEBOX, WS_MAXIMIZEBOX. */
    private const val FRAME_STYLES = 0x00C00000 or 0x00040000 or 0x00080000 or 0x00020000 or 0x00010000
}
