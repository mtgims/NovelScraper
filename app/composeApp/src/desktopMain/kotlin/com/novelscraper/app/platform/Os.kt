package com.novelscraper.app.platform

/** Which desktop this is. A handful of things differ, and they are all here. */
object Os {
    private val name: String = System.getProperty("os.name").orEmpty().lowercase()

    val isWindows: Boolean = name.startsWith("windows")
    val isLinux: Boolean = name.startsWith("linux")
}
