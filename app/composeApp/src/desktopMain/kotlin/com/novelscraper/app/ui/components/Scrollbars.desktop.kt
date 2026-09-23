package com.novelscraper.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun GridScrollbar(state: LazyGridState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier)
}

@Composable
actual fun ColumnScrollbar(state: ScrollState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier)
}

@Composable
actual fun ListScrollbar(state: LazyListState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier)
}
