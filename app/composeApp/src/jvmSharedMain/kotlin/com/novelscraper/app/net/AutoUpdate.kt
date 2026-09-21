package com.novelscraper.app.net

import com.novelscraper.app.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks the server to queue incremental updates for the user's due books (older than
 * the configured auto-update interval). Fired when the app comes to the foreground
 * and when the user signs in — waiting briefly for the scrape relay so
 * Cloudflare-gated sources get fetched through this phone's IP (the server can't
 * reach them itself). The server decides which books are actually due, so repeat
 * calls are harmless; we only throttle to avoid needless calls on rapid app
 * switches, and skip quietly when not signed in.
 */
object AutoUpdate {
    private const val TAG = "AutoUpdate"
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L  // at most once / 15 min
    private const val RELAY_WAIT_MS = 8_000L             // don't block forever on the relay

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastSuccess = 0L

    /** Fire-and-forget; no-ops if throttled or not signed in. Not guarded against
     *  concurrent runs on purpose: the server is idempotent (it only queues books
     *  actually due), so a foreground check overlapping a sign-in check is fine —
     *  and guarding on "in flight" would drop the sign-in check while a slow
     *  foreground check waits on the relay. Throttle is keyed on the last SUCCESS,
     *  so a signed-out attempt (401) doesn't block a real sign-in right after. */
    fun trigger() {
        if (System.currentTimeMillis() - lastSuccess < MIN_INTERVAL_MS) return
        scope.launch {
            try {
                // Prefer the relay being up (gated sources), but don't hang if it
                // never connects — non-gated books still update server-side.
                withTimeoutOrNull(RELAY_WAIT_MS) { ScrapeRelay.connected.first { it } }
                val r = Net.api.updateDue()
                lastSuccess = System.currentTimeMillis()
                Log.i(TAG, "update-due queued=${r.queued}")
            } catch (e: Exception) {
                Log.d(TAG, "update-due skipped: ${e.message}")  // not signed in / offline
            }
        }
    }
}
