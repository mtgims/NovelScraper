package com.novelscraper.app.platform

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.awt.Frame

/** The main window, for parenting native dialogs (file pickers). */
val LocalAppWindow = staticCompositionLocalOf<Frame?> { null }

/** showToast() on desktop: messages queue here and [ToastHost] shows them one at
 *  a time as snackbars at the bottom of the window. */
internal object Toasts {
    val queue = Channel<Pair<String, Boolean>>(capacity = 16)
}

@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val state = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        Toasts.queue.receiveAsFlow().collect { (msg, long) ->
            state.showSnackbar(msg, duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short)
        }
    }
    // In a popup that exists only while a message shows: created after any open
    // dialog, it is the top layer, so a toast is never hidden behind a dialog
    // (Android toasts float above everything too).
    if (state.currentSnackbarData != null) {
        Popup(
            alignment = Alignment.BottomCenter,
            properties = PopupProperties(focusable = false),
        ) {
            SnackbarHost(state, modifier)
        }
    }
}
