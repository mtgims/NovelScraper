package com.novelscraper.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// A phone scrolls with a finger and shows its own hint while it does.
@Composable
actual fun GridScrollbar(state: LazyGridState, modifier: Modifier) = Unit

@Composable
actual fun ColumnScrollbar(state: ScrollState, modifier: Modifier) = Unit
