package com.novelscraper.app.tts

import com.novelscraper.app.net.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Voice model downloads, kept outside any screen.
 *
 * A download of 65 to 335 MB used to belong to the settings page that started
 * it: leaving the page carried on fetching but forgot the progress, and coming
 * back offered the download again, which ran a second one into the same
 * temporary files. Here a download outlives whatever page shows it, and one
 * runs at a time (they share the downloader's temporary archive).
 */
object ModelDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow<Map<String, KokoroDownloader.Progress>>(emptyMap())

    /** The last word on each model downloaded since the app started, by model id. */
    val progress: StateFlow<Map<String, KokoroDownloader.Progress>> = _progress.asStateFlow()

    val busy: Boolean get() = _progress.value.values.any { it.running }

    /** Starts fetching [id]; false when a download is already running. [onDone] runs once it is in place. */
    fun start(id: String, onDone: () -> Unit = {}): Boolean {
        synchronized(this) {
            if (busy) return false
            _progress.update { it + (id to KokoroDownloader.Progress.Downloading(0, 1)) }
        }
        scope.launch {
            KokoroDownloader.download(Net.client, id) { p ->
                _progress.update { it + (id to p) }
                if (p is KokoroDownloader.Progress.Done) onDone()
            }
        }
        return true
    }
}

val KokoroDownloader.Progress.running: Boolean
    get() = this is KokoroDownloader.Progress.Downloading || this is KokoroDownloader.Progress.Extracting
