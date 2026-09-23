package com.novelscraper.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A scrollbar, where the platform has them.
 *
 * A window with no scrollbar gives no sense of how much is below, and a pointer
 * has nothing to grab: a phone's fling gesture is not an answer here. On the
 * phone these are nothing at all, which is correct.
 */
@Composable
expect fun GridScrollbar(state: LazyGridState, modifier: Modifier)

@Composable
expect fun ColumnScrollbar(state: ScrollState, modifier: Modifier)

@Composable
expect fun ListScrollbar(state: LazyListState, modifier: Modifier)
