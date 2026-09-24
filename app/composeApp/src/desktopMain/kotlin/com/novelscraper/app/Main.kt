package com.novelscraper.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import com.novelscraper.app.platform.Os
import com.novelscraper.app.platform.WindowsFrame
import com.novelscraper.app.ui.components.TitleBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.novelscraper.app.data.LibraryPrefs
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.update.AppUpdates
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.library.Library
import com.novelscraper.app.net.Account
import com.novelscraper.app.net.Net
import com.novelscraper.app.net.ScrapeRelay
import com.novelscraper.app.net.buildImageLoader
import com.novelscraper.app.platform.DesktopDirs
import com.novelscraper.app.platform.LocalAppWindow
import com.novelscraper.app.platform.PropertiesStore
import com.novelscraper.app.platform.ToastHost
import com.novelscraper.app.platform.disposeSiteCheckBrowser
import com.novelscraper.app.platform.settingsStore
import com.novelscraper.app.tts.DesktopTtsPlayer
import com.novelscraper.app.tts.MprisPlayer
import com.novelscraper.app.tts.TtsController
import com.novelscraper.app.ui.AppRoot
import com.novelscraper.app.ui.theme.NovelScraperTheme
import com.novelscraper.app.ui.theme.ThemeController
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Image
import java.io.File

/** Everything App.onCreate does on Android, before the first window. */
fun initApp() {
    Net.init()
    Account.init()
    Extensions.init(Net.client)
    AppUpdates.init(Net.client)
    // A quiet look at the releases page on startup: a reader who didn't
    // ask isn't told that GitHub was unreachable.
    AppUpdates.check(quietly = true)
    Library.init()
    ReaderPrefs.init()
    LibraryPrefs.init()
    ThemeController.init()
    TtsController.player = DesktopTtsPlayer
    SingletonImageLoader.setSafe { ctx ->
        buildImageLoader(ctx) {
            // Left to itself Coil keeps a fifth of the heap's ceiling in decoded
            // images, which on a 16 GB machine is 800 MB of covers held in
            // memory. A screen of covers is a few megabytes; what scrolls away
            // comes back from the disk cache in a moment.
            memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(48L * 1024 * 1024)
                    .build()
            }
            diskCache {
                DiskCache.Builder()
                    .directory(File(DesktopDirs.cache, "images").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
        }
    }
}

private val icon by lazy {
    val bytes = checkNotNull(object {}.javaClass.getResourceAsStream("/icon.png")).use { it.readBytes() }
    BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}

fun main(args: Array<String>) {
    // A throwaway copy of the app trying a voice on the graphics card (see
    // GpuVoice.probe): nothing else of the app starts.
    if (args.firstOrNull() == com.novelscraper.app.tts.GpuVoice.PROBE_ARG) {
        kotlin.system.exitProcess(com.novelscraper.app.tts.GpuVoice.runProbe(args.drop(1)))
    }
    initApp()
    com.novelscraper.app.ui.screen.SettingsHooks.narration = {
        com.novelscraper.app.ui.components.GpuAccelerationSetting()
    }
    // Settings are saved in the background; make sure pending saves land however
    // the app exits (window close, logout, SIGTERM).
    Runtime.getRuntime().addShutdownHook(Thread { PropertiesStore.flush() })
    // The desktop app is "in the foreground" while it runs: keep the relay up so
    // scrapes go through this computer's connection, check for due updates and
    // bring in the server's library (when signed in).
    Account.onForeground()
    if (DesktopDirs.installedInsideData) {
        com.novelscraper.app.platform.Log.w("Main", "installed inside the data folder ${DesktopDirs.data}")
        com.novelscraper.app.platform.showToast(
            "NovelScraper is installed inside its own data folder, so updating or uninstalling it " +
                "deletes your library. Reinstall it somewhere else.",
            long = true,
        )
    }
    // Media keys and the desktop's media widget drive narration (MPRIS).
    MprisPlayer.start(onQuit = { shutdown(); kotlin.system.exitProcess(0) })

    application {
        val windowPrefs = settingsStore("window")
        val state = WindowState(
            placement = if (windowPrefs.getBoolean("maximized", false)) WindowPlacement.Maximized
                        else WindowPlacement.Floating,
            position = if (windowPrefs.getInt("x", Int.MIN_VALUE) == Int.MIN_VALUE) WindowPosition.PlatformDefault
                       else WindowPosition(windowPrefs.getInt("x", 0).dp, windowPrefs.getInt("y", 0).dp),
            size = DpSize(windowPrefs.getInt("width", 1280).dp, windowPrefs.getInt("height", 860).dp),
        )
        Window(
            onCloseRequest = {
                windowPrefs.putBoolean("maximized", state.placement == WindowPlacement.Maximized)
                if (state.placement == WindowPlacement.Floating) {
                    windowPrefs.putInt("width", state.size.width.value.toInt())
                    windowPrefs.putInt("height", state.size.height.value.toInt())
                    (state.position as? WindowPosition.Absolute)?.let {
                        windowPrefs.putInt("x", it.x.value.toInt())
                        windowPrefs.putInt("y", it.y.value.toInt())
                    }
                }
                shutdown()
                exitApplication()
            },
            state = state,
            title = "NovelScraper",
            icon = icon,
            // Windows draws its own bar, in its own colours, above an app that has
            // chosen its own: the app draws that bar itself instead. Elsewhere the
            // desktop decides, which on a tiling one means no bar at all.
            undecorated = drawsOwnTitleBar,
            resizable = true,
        ) {
            window.minimumSize = java.awt.Dimension(420, 560)
            // On Windows the window gets its system frame back, minus the title
            // bar, which the app draws (see WindowsFrame).
            if (Os.isWindows) {
                androidx.compose.runtime.LaunchedEffect(Unit) { WindowsFrame.install(window) }
            }
            // An undecorated window maximises over everything, taskbar included,
            // because nothing is left to tell it where the usable screen ends.
            // These are those bounds, and they are asked for again whenever the
            // screen arrangement changes. A window with a frame knows them already.
            if (drawsOwnTitleBar) {
                androidx.compose.runtime.LaunchedEffect(state.placement, WindowsFrame.installed) {
                    if (WindowsFrame.installed) return@LaunchedEffect
                    runCatching {
                        val screen = window.graphicsConfiguration
                        val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(screen)
                        val b = screen.bounds
                        window.maximizedBounds = java.awt.Rectangle(
                            b.x + insets.left,
                            b.y + insets.top,
                            b.width - insets.left - insets.right,
                            b.height - insets.top - insets.bottom,
                        )
                    }
                }
            }
            CompositionLocalProvider(LocalAppWindow provides window) {
                AppContent(
                    titleBar = if (drawsOwnTitleBar) {
                        { TitleBar(state) { window.dispatchEvent(
                            java.awt.event.WindowEvent(window, java.awt.event.WindowEvent.WINDOW_CLOSING),
                        ) } }
                    } else null,
                )
            }
        }
    }
}

/**
 * Whether the app draws its own title bar rather than letting the desktop draw
 * one. Windows always does: its bar comes in its own colours and sits above an
 * app in whichever colours the reader chose. A Linux desktop decides for itself,
 * and a tiling one draws nothing at all, so there it is off unless asked for
 * with NOVELSCRAPER_TITLEBAR=1.
 */
private val drawsOwnTitleBar: Boolean =
    Os.isWindows || System.getenv("NOVELSCRAPER_TITLEBAR") == "1"

/** The app inside the window (also what the UI tests render). */
@androidx.compose.runtime.Composable
fun AppContent(titleBar: (@Composable () -> Unit)? = null) {
    NovelScraperTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // The title bar, where the app draws its own, is inside the theme, so
            // it is the same surface and the same colours as the sidebar under it.
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            titleBar?.invoke()
            Box(Modifier.fillMaxSize()) {
                AppRoot()
                ToastHost(Modifier.padding(bottom = 96.dp))
            }
            }
        }
    }
}

private fun shutdown() {
    TtsController.stop()
    MprisPlayer.stop()
    disposeSiteCheckBrowser()
    ScrapeRelay.stop()
    com.novelscraper.app.tts.GpuVoice.stopProbe()
    // Releasing the voice waits for a sentence being synthesized to finish.
    // The process is ending and the system takes the memory back anyway, so
    // closing the window never waits on it for more than a moment.
    Thread { DesktopTtsPlayer.release() }.apply { isDaemon = true; start() }.join(2_000)
    PropertiesStore.flush()
}
