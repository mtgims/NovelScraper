package com.novelscraper.app.platform

import androidx.compose.runtime.Composable
import com.novelscraper.app.extensions.SiteChecks
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** True where the app can show a web page inside itself (the phone's WebView). */
expect val hasInAppBrowser: Boolean

/**
 * A web page inside the app, taking the screen. A page opened for a site's
 * browser check ([check]) closes itself once the site lets the app in; whatever
 * the site gave the page goes to the sources when it closes, either way.
 */
@Composable
expect fun InAppBrowser(url: String, check: Boolean, onClose: () -> Unit)

/**
 * Web pages the app was asked to show. Asked for from anywhere (a source's
 * page, a novel, a check's prompt); the navigation picks them up and opens
 * them on screen.
 */
object WebPages {
    data class Request(val url: String, val check: Boolean)

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 4)
    val requests: SharedFlow<Request> = _requests.asSharedFlow()

    /** A site's page: inside the app where it can show one, else in the browser. */
    fun open(url: String) {
        if (hasInAppBrowser) _requests.tryEmit(Request(url, check = false)) else openInBrowser(url)
    }

    /** A site's browser check, for the reader to pass in the app's web view. */
    fun openCheck(url: String) {
        SiteChecks.beginInApp(url)
        _requests.tryEmit(Request(url, check = true))
    }
}
