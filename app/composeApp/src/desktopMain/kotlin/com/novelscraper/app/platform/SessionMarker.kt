package com.novelscraper.app.platform

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/**
 * Notices a session that ended without closing: a crash in native code, the
 * process killed, the power cut. [begin] writes [marker] and every proper
 * exit removes it ([end], from the shutdown hook, which runs for a closed
 * window, an update's restart and a system shutdown alike), so a marker found
 * at the next start means the last session died, and with it the end of the
 * log and what the app was doing then.
 *
 * On 2026-11-17 the app aborted after 37 minutes of narration and nothing
 * said so; it took a core dump to find out it had happened at all.
 */
class SessionMarker(private val marker: File, private val log: File) {

    data class Report(
        val started: String,
        val version: String,
        /** What the app was doing when it died, as the reader would say it. */
        val doing: String?,
        val logTail: List<String>,
    ) {
        /** What "Copy details" puts on the clipboard. */
        fun details(): String = buildString {
            appendLine("NovelScraper $version closed unexpectedly")
            appendLine("Session started: $started")
            if (doing != null) appendLine("Doing: $doing")
            appendLine("System: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine()
            appendLine("Last lines of the log:")
            logTail.forEach { appendLine(it) }
        }
    }

    private var props = Properties()

    /** At start, holding the instance lock: the report on the last session if
     *  it died, then this session marked as running. */
    @Synchronized
    fun begin(version: String): Report? {
        val report = if (marker.isFile) {
            val p = Properties()
            runCatching { marker.reader().use { p.load(it) } }
            Report(
                started = p.getProperty("started") ?: "?",
                version = p.getProperty("version") ?: "?",
                doing = p.getProperty("doing"),
                logTail = runCatching { tail(log, LOG_LINES) }.getOrDefault(emptyList()),
            )
        } else null
        props = Properties().apply {
            setProperty("started", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            setProperty("version", version)
            setProperty("pid", ProcessHandle.current().pid().toString())
        }
        save()
        return report
    }

    /** What the app is doing now, for the report should it die; null for
     *  nothing in particular. */
    @Synchronized
    fun doing(what: String?) {
        if (what == props.getProperty("doing")) return
        if (what == null) props.remove("doing") else props.setProperty("doing", what)
        save()
    }

    /** A proper exit. */
    @Synchronized
    fun end() {
        marker.delete()
    }

    private fun save() {
        runCatching {
            marker.parentFile?.mkdirs()
            val tmp = File(marker.path + ".tmp")
            tmp.writer().use { props.store(it, null) }
            tmp.renameTo(marker) || run { marker.delete(); tmp.renameTo(marker) }
        }
    }

    companion object {
        const val LOG_LINES = 200

        /** The last [n] lines of [file] (the log is kept to a megabyte, so
         *  reading it whole is never much). */
        internal fun tail(file: File, n: Int): List<String> {
            if (!file.isFile) return emptyList()
            return file.readLines().takeLast(n)
        }
    }
}
