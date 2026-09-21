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
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.net.AutoUpdate
import com.novelscraper.app.net.Net
import com.novelscraper.app.net.ScrapeRelay
import com.novelscraper.app.net.buildImageLoader
import com.novelscraper.app.platform.DesktopDirs
import com.novelscraper.app.platform.LocalAppWindow
import com.novelscraper.app.platform.PropertiesStore
import com.novelscraper.app.platform.ToastHost
import com.novelscraper.app.platform.settingsStore
import com.novelscraper.app.tts.DesktopTtsPlayer
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
    ReaderPrefs.init()
    ThemeController.init()
    TtsController.player = DesktopTtsPlayer
    SingletonImageLoader.setSafe { ctx ->
        buildImageLoader(ctx) {
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

fun main() {
    initApp()
    // Settings are saved in the background; make sure pending saves land however
    // the app exits (window close, logout, SIGTERM).
    Runtime.getRuntime().addShutdownHook(Thread { PropertiesStore.flush() })
    // The desktop app is "in the foreground" while it runs: keep the relay up so
    // scrapes go through this computer's connection, and check for due updates.
    ScrapeRelay.start()
    AutoUpdate.trigger()

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
        ) {
            window.minimumSize = java.awt.Dimension(420, 560)
            CompositionLocalProvider(LocalAppWindow provides window) {
                AppContent()
            }
        }
    }
}

/** The app inside the window (also what the UI tests render). */
@androidx.compose.runtime.Composable
fun AppContent() {
    NovelScraperTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                AppRoot()
                ToastHost(Modifier.padding(bottom = 96.dp))
            }
        }
    }
}

private fun shutdown() {
    TtsController.stop()
    ScrapeRelay.stop()
    DesktopTtsPlayer.release()
    PropertiesStore.flush()
}
