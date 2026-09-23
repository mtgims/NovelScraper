package com.novelscraper.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** jpackage tells the app its own version; the fallback is only for a run from
 *  source, where there is no packaged version to read. */
actual val appVersion: String
    get() = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: "0.0.0"

actual val updateAssetName: String =
    if (Os.isWindows) "novelscraper-setup.exe" else "novelscraper-x86_64.AppImage"

/**
 * Put a new build in place: an installer on Windows, the AppImage itself on
 * Linux.
 *
 * On Linux it replaces the file this app is running from, then start the new one and
 * stand down.
 *
 * The running file is a squashfs the app has mounted and is reading its own
 * classes out of, so it is never written over: the new build is moved into
 * place, which leaves the old file's contents alone for as long as this process
 * still needs them, and only the name now points at the new one.
 */
actual suspend fun installUpdate(file: File): Boolean = withContext(Dispatchers.IO) {
    // Windows ships an installer, and an installer is what replaces the app:
    // it is started, it asks what it asks, and this process steps aside so the
    // files it is replacing are not in use.
    if (Os.isWindows) {
        return@withContext try {
            ProcessBuilder(file.absolutePath).start()
            Thread.sleep(1_200)
            kotlin.system.exitProcess(0)
        } catch (e: Exception) {
            Log.w("Updates", "couldn't start the installer: ${e.message}")
            false
        }
    }
    val running = System.getenv("APPIMAGE")?.let { File(it) }
    if (running == null || !running.isFile) {
        Log.w("Updates", "not running from an AppImage; opening the folder instead")
        openInBrowser(file.parentFile.toURI().toString())
        return@withContext false
    }
    try {
        file.setExecutable(true, false)
        val staged = File(running.parentFile, running.name + ".new")
        staged.delete()
        Files.move(file.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(
            staged.toPath(), running.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
        )
        running.setExecutable(true, false)
        Log.i("Updates", "updated ${running.name}; restarting")
        ProcessBuilder(running.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        // Give the new process a moment to take over the window before this one
        // goes, so the app never appears to vanish.
        Thread.sleep(1_200)
        kotlin.system.exitProcess(0)
    } catch (e: Exception) {
        Log.w("Updates", "couldn't put the update in place: ${e.message}")
        false
    }
}
