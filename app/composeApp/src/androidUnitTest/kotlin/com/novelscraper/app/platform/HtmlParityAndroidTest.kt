package com.novelscraper.app.platform

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android side of the html-parity check: runs the framework's real Html code
 * (via Robolectric) over the fixtures in src/htmlParity and compares with the
 * committed goldens, which the desktop port is held to (HtmlParityDesktopTest).
 *
 * -PhtmlParityRecord=true rewrites the goldens from Android's output.
 * -PhtmlParityExtra=<dir> also converts every *.html in <dir> and writes the
 * results next to them as *.android.txt (for checking real chapters locally
 * without committing them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HtmlParityAndroidTest {
    private val root = File(checkNotNull(System.getProperty("htmlParity.dir")) { "run through Gradle" })

    @Test fun fixturesMatchGoldens() {
        val record = System.getProperty("htmlParity.record") == "true"
        val fixtures = File(root, "fixtures").listFiles { f -> f.name.endsWith(".html") }!!.sortedBy { it.name }
        assertTrue(fixtures.isNotEmpty())
        for (f in fixtures) {
            val actual = htmlToPlain(f.readText())
            val golden = File(root, "golden/${f.nameWithoutExtension}.txt")
            if (record) golden.writeText(actual)
            else assertEquals(golden.readText(), actual, "Android output changed for ${f.name}")
        }
    }

    @Test fun extraDir() {
        val dir = System.getProperty("htmlParity.extra")?.takeIf { it.isNotBlank() } ?: return
        File(dir).listFiles { f -> f.name.endsWith(".html") }!!.forEach { f ->
            File(f.parentFile, "${f.nameWithoutExtension}.android.txt").writeText(htmlToPlain(f.readText()))
        }
    }
}
