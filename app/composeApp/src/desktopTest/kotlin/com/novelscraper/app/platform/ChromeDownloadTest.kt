package com.novelscraper.app.platform

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Chrome fetched for a machine without one: which of several is used. */
class ChromeDownloadTest {
    private val home = File(appFilesDir(), "chrome")
    private val inside = if (Os.isWindows) "chrome-win64/chrome.exe" else "chrome-linux64/chrome"

    private fun install(version: String) =
        File(home, "$version/$inside").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true) }

    @AfterTest fun clean() { home.deleteRecursively() }

    /** Versions were compared as text, so "99" came after "156". */
    @Test fun theNewestVersionIsTheOneUsed() {
        install("99.0.4844.51")
        install("156.0.8078.47")
        install("156.0.8078.9")
        assertEquals("156.0.8078.47", ChromeDownload.installed?.parentFile?.parentFile?.name)
    }

    @Test fun aBrowserStillBeingUnpackedIsNeverUsed() {
        install("156.0.8078.47")
        install("157.0.1.1.partial")           // an update, mid-way
        assertEquals("156.0.8078.47", ChromeDownload.installed?.parentFile?.parentFile?.name)
    }

    @Test fun olderVersionsAreRemoved() {
        install("150.0.1.1"); install("156.0.8078.47")
        ChromeDownload.removeOlder()
        assertEquals(listOf("156.0.8078.47"), home.listFiles()!!.filter { it.isDirectory }.map { it.name })
    }

    @Test fun aDownloadIsCheckedAgainstGooglesMd5() {
        val f = File.createTempFile("chrome", ".zip").apply { writeText("the browser"); deleteOnExit() }
        val md5 = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("MD5").digest("the browser".toByteArray()))
        assertEquals(true, ChromeDownload.verify(f, listOf("crc32c=AAAAAA==", "md5=$md5")))
        assertEquals(true, ChromeDownload.verify(f, listOf("crc32c=AAAAAA==,md5=$md5")), "one header, both")
        assertEquals(false, ChromeDownload.verify(f, listOf("md5=AAAAAAAAAAAAAAAAAAAAAA==")))
        assertEquals(true, ChromeDownload.verify(f, emptyList()), "none given: nothing to check")
    }
}
