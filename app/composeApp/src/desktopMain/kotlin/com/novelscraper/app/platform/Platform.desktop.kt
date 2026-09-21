package com.novelscraper.app.platform

import androidx.compose.runtime.Composable
import java.io.File
import java.net.URLEncoder

// Desktop (Linux/Windows) platform. Phase 1 only needs this target to compile,
// which proves the shared code has no Android dependencies; the pieces marked
// TODO("Phase 2") are designed and filled in with the desktop app.

actual fun settingsStore(name: String): KeyValueStore = TODO("Phase 2")

actual val isDebugBuild: Boolean = false

actual fun appFilesDir(): File = TODO("Phase 2")

actual fun appCacheDir(): File = TODO("Phase 2")

actual fun htmlToPlain(html: String): String = TODO("Phase 2")

actual fun encodeRouteArg(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

actual fun showToast(message: String, long: Boolean): Unit = TODO("Phase 2")

actual object Log {
    actual fun d(tag: String, msg: String) = println("D/$tag: $msg")
    actual fun i(tag: String, msg: String) = println("I/$tag: $msg")
    actual fun w(tag: String, msg: String) = System.err.println("W/$tag: $msg")
    actual fun e(tag: String, msg: String, t: Throwable?) {
        System.err.println("E/$tag: $msg")
        t?.printStackTrace()
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit): Unit = TODO("Phase 2")

@Composable
actual fun SystemBarsVisible(visible: Boolean): Unit = TODO("Phase 2")

@Composable
actual fun rememberEpubPicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit = TODO("Phase 2")

@Composable
actual fun rememberFileSaver(mimeType: String, onTarget: (SaveTarget?) -> Unit): (String) -> Unit =
    TODO("Phase 2")

@Composable
actual fun rememberNarrationPermission(action: () -> Unit): () -> Unit = action

@Composable
actual fun rememberSystemVoices(): List<SystemVoice> = TODO("Phase 2")
