package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.JobCreate
import com.novelscraper.app.data.JobRead
import com.novelscraper.app.net.Net
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
}

class NewScrapeViewModel : ViewModel() {
    private val _ui = MutableStateFlow<ScrapeUi>(ScrapeUi.Idle)
    val ui: StateFlow<ScrapeUi> = _ui.asStateFlow()

    fun scrape(url: String, cpv: Int?, delay: Float?, concurrency: Int?) {
        if (_ui.value is ScrapeUi.Submitting || url.isBlank()) return
        _ui.value = ScrapeUi.Submitting
        viewModelScope.launch {
            // Give the relay a moment to connect so the scrape's fetches route
            // through this phone's IP from the start (best-effort; falls back to a
            // server-side fetch if it isn't up in time).
            ScrapeRelay.start()
            if (!ScrapeRelay.connected.value) {
                withTimeoutOrNull(5000) { ScrapeRelay.connected.first { it } }
            }
            _ui.value = try {
                ScrapeUi.Done(Net.api.createJob(JobCreate(url.trim(), cpv, delay, concurrency)))
            } catch (e: HttpException) {
                ScrapeUi.Error(detailOf(e) ?: "Couldn't start the scrape (${e.code()}).")
            } catch (e: Exception) {
                ScrapeUi.Error("Can't reach the server.")
            }
        }
    }

    fun reset() { _ui.value = ScrapeUi.Idle }

    private fun detailOf(e: HttpException): String? = try {
        e.response()?.errorBody()?.string()?.let { JSONObject(it).optString("detail").ifBlank { null } }
    } catch (_: Exception) { null }
}
