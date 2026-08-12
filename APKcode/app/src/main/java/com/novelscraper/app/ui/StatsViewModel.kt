package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.StatsRead
import com.novelscraper.app.net.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface StatsUi {
    data object Loading : StatsUi
    data class Error(val message: String) : StatsUi
    data class Data(val stats: StatsRead) : StatsUi
}

class StatsViewModel : ViewModel() {
    private val _ui = MutableStateFlow<StatsUi>(StatsUi.Loading)
    val ui: StateFlow<StatsUi> = _ui.asStateFlow()

    init { load() }

    fun load() {
        _ui.value = StatsUi.Loading
        viewModelScope.launch {
            _ui.value = try {
                StatsUi.Data(Net.api.stats())
            } catch (e: Exception) {
                StatsUi.Error("Couldn't load statistics.")
            }
        }
    }
}
