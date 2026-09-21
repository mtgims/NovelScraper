package com.novelscraper.app.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Desktop side of the html-parity check (see HtmlParityAndroidTest): the port
 *  must reproduce Android's text exactly, so both split sentences the same way. */
class HtmlParityDesktopTest {
    private val root = File(checkNotNull(System.getProperty("htmlParity.dir")) { "run through Gradle" })

    @Test fun fixturesMatchAndroidGoldens() {
        val fixtures = File(root, "fixtures").listFiles { f -> f.name.endsWith(".html") }!!.sortedBy { it.name }
        assertTrue(fixtures.isNotEmpty())
        for (f in fixtures) {
            val golden = File(root, "golden/${f.nameWithoutExtension}.txt").readText()
            // Sentences.plain()/ranges() are shared code applied to this text, so equal
            // text means equal sentence indices.
            assertEquals(golden, HtmlPlainText.convert(f.readText()), "text differs from Android for ${f.name}")
        }
    }

    /** With -PhtmlParityExtra=<dir>, compares against the *.android.txt files
     *  HtmlParityAndroidTest wrote there. */
    @Test fun extraDirMatchesAndroid() {
        val dir = System.getProperty("htmlParity.extra")?.takeIf { it.isNotBlank() } ?: return
        val files = File(dir).listFiles { f -> f.name.endsWith(".html") }!!
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val android = File(f.parentFile, "${f.nameWithoutExtension}.android.txt").readText()
            assertEquals(android, HtmlPlainText.convert(f.readText()), "text differs from Android for ${f.name}")
        }
    }
}
