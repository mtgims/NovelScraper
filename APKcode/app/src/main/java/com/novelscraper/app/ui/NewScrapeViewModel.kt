package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.JobCreate
import com.novelscraper.app.data.JobRead
import com.novelscraper.app.net.Net
import com.novelscraper.app.net.NuExtract
import com.novelscraper.app.net.NuGroup
import com.novelscraper.app.net.NuResolver
import com.novelscraper.app.net.ScrapeRelay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import retrofit2.HttpException

sealed interface ScrapeUi {
    data object Idle : ScrapeUi
    data object Submitting : ScrapeUi
    data class Error(val message: String) : ScrapeUi
    data class Done(val job: JobRead) : ScrapeUi
    // Pasted a NovelUpdates series link: groups read offscreen, awaiting the user's pick.
    data class ChooseNu(val groups: List<NuGroup>) : ScrapeUi
    // Couldn't read NU offscreen (not logged in / challenge): open the visible browser.
    data class NeedsNuLogin(val url: String) : ScrapeUi
}

class NewScrapeViewModel : ViewModel() {
    private val _ui = MutableStateFlow<ScrapeUi>(ScrapeUi.Idle)
    val ui: StateFlow<ScrapeUi> = _ui.asStateFlow()

    fun scrape(url: String, cpv: Int?, delay: Float?, concurrency: Int?) {
        if (_ui.value is ScrapeUi.Submitting || url.isBlank()) return
        val u = url.trim()
        // A NovelUpdates series link isn't scrapeable directly — resolve it to a
        // translator via the groups on the page (offscreen if we're logged in).
        if (NuExtract.isSeriesUrl(u)) {
            _ui.value = ScrapeUi.Submitting
            viewModelScope.launch {
                val groups = NuResolver.extractGroups(u)
                _ui.value = if (groups.isEmpty()) ScrapeUi.NeedsNuLogin(u)
                            else ScrapeUi.ChooseNu(groups)
            }
            return
        }
        _ui.value = ScrapeUi.Submitting
        viewModelScope.launch { _ui.value = submitJob(u, cpv, delay, concurrency) }
    }

    /** A group was picked from the NU chooser: resolve it to the translator's site
     *  and scrape the whole novel (rewound to chapter 1). */
    fun pickNuGroup(g: NuGroup) {
        if (_ui.value is ScrapeUi.Submitting) return
        _ui.value = ScrapeUi.Submitting
        viewModelScope.launch {
            val tl = NuResolver.resolveExtnu(g.extnu)
            _ui.value = if (tl == null) ScrapeUi.Error("Couldn't open ${g.name}'s site.")
                        else submitJob(NuExtract.toChapterOne(tl), null, null, null)
        }
    }

    private suspend fun submitJob(url: String, cpv: Int?, delay: Float?, concurrency: Int?): ScrapeUi {
        // Give the relay a moment to connect so fetches route through this phone's IP
        // from the start (and so JS sites can be rendered); falls back server-side.
        ScrapeRelay.start()
        if (!ScrapeRelay.connected.value) {
            withTimeoutOrNull(5000) { ScrapeRelay.connected.first { it } }
        }
        return try {
            ScrapeUi.Done(Net.api.createJob(JobCreate(url.trim(), cpv, delay, concurrency)))
        } catch (e: HttpException) {
            ScrapeUi.Error(detailOf(e) ?: "Couldn't start the scrape (${e.code()}).")
        } catch (e: Exception) {
            ScrapeUi.Error("Can't reach the server.")
        }
    }

    fun reset() { _ui.value = ScrapeUi.Idle }

    private fun detailOf(e: HttpException): String? = try {
        e.response()?.errorBody()?.string()?.let { JSONObject(it).optString("detail").ifBlank { null } }
    } catch (_: Exception) { null }
}
