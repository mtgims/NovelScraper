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

    val data: File by lazy {
        if (windows) File(env("LOCALAPPDATA") ?: home, "NovelScraper")
        else File(env("XDG_DATA_HOME") ?: File(home, ".local/share"), APP)
    }

    val cache: File by lazy {
        if (windows) File(env("LOCALAPPDATA") ?: home, "NovelScraper/Cache")
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
