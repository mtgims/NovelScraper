package com.novelscraper.app.net

import java.io.File

/** Naming files in the user's Downloads folder. */
object Downloads {
    /** Claims the first free name among "name.ext", "name (1).ext", ... by creating
     *  it empty, so two saves of the same thing never pick the same file. */
    internal fun reserve(dir: File, fileName: String): File {
        val stem = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 0
        while (true) {
            val candidate = File(dir, if (n == 0) fileName else "$stem ($n)$ext")
            if (candidate.createNewFile()) return candidate
            n++
        }
    }
}
