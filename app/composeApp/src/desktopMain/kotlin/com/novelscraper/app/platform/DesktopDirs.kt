package com.novelscraper.app.platform

import java.io.File

/**
 * Where the desktop app keeps things. Linux follows the XDG base directories
 * (settings in ~/.config, models in ~/.local/share, scratch in ~/.cache);
 * Windows uses %APPDATA% / %LOCALAPPDATA%.
 */
object DesktopDirs {
    private const val APP = "novelscraper"
    private val home = File(System.getProperty("user.home"))
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private fun env(name: String): File? = System.getenv(name)?.takeIf { it.isNotBlank() }?.let(::File)

    val config: File by lazy {
        if (windows) File(env("APPDATA") ?: home, "NovelScraper")
        else File(env("XDG_CONFIG_HOME") ?: File(home, ".config"), APP)
    }

    /**
     * The library, the sources, the voices and the browser's profile.
     *
     * On Windows this is not %LOCALAPPDATA%\NovelScraper, because that is where
     * the installer puts the program, and an update removes the old version's
     * folder whole before installing the new one: everything kept beside the
     * program went with it, every time. Up to 0.42.1 the data lived there.
     */
    val data: File by lazy {
        if (windows) File(env("LOCALAPPDATA") ?: home, "NovelScraper Data")
        else File(env("XDG_DATA_HOME") ?: File(home, ".local/share"), APP)
    }

    /**
     * True when the program itself is installed inside [data], which only a
     * reader choosing that folder in the installer can bring about. An update or
     * an uninstall would then delete the library along with the program.
     */
    val installedInsideData: Boolean by lazy {
        val app = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() } ?: return@lazy false
        runCatching {
            File(app).canonicalFile.toPath().startsWith(data.canonicalFile.toPath())
        }.getOrDefault(false)
    }

    val cache: File by lazy {
        if (windows) File(data, "Cache")
        else File(env("XDG_CACHE_HOME") ?: File(home, ".cache"), APP)
    }

    /** The user's Downloads folder: $XDG_DOWNLOAD_DIR, then the XDG_DOWNLOAD_DIR
     *  line of ~/.config/user-dirs.dirs (what `xdg-user-dir DOWNLOAD` reads), then
     *  ~/Downloads. */
    val downloads: File
        get() = env("XDG_DOWNLOAD_DIR") ?: userDirsDownload() ?: File(home, "Downloads")

    private fun userDirsDownload(): File? {
        if (windows) return null
        val f = File(env("XDG_CONFIG_HOME") ?: File(home, ".config"), "user-dirs.dirs")
        val line = runCatching { f.readLines() }.getOrNull()
            ?.firstOrNull { it.trimStart().startsWith("XDG_DOWNLOAD_DIR=") } ?: return null
        val value = line.substringAfter('=').trim().removeSurrounding("\"")
        return File(value.replace("\$HOME", home.path)).takeIf { value.isNotBlank() }
    }
}
