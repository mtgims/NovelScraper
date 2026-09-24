package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.JobRead
import com.novelscraper.app.net.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProgressViewModel : ViewModel() {
    private val _jobs = MutableStateFlow<List<JobRead>>(emptyList())
    val jobs: StateFlow<List<JobRead>> = _jobs.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            runCatching { Net.api.jobs() }.onSuccess { _jobs.value = it; _loaded.value = true }
        }
    }

    fun cancel(id: String) = act { Net.api.cancelJob(id) }
    fun delete(id: String) = act { Net.api.deleteJob(id) }
    fun clearFinished() = act { Net.api.clearFinishedJobs() }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
            refresh()
        }
    }
}
