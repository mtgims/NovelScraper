package com.novelscraper.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.net.AutoUpdate
import com.novelscraper.app.net.Net
import com.novelscraper.app.net.ScrapeRelay
import com.novelscraper.app.ui.theme.ThemeController

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Net.init(this)
        ReaderPrefs.init(this)
        ThemeController.init(this)
        // Keep a scrape-relay WebSocket open while the app is foregrounded, so
        // scrapes fetch through this phone's IP (bypassing the server's Cloudflare
        // block). Dropped when backgrounded — the server then fetches server-side.
        registerActivityLifecycleCallbacks(ForegroundRelay)
    }

    /** Ref-counts started activities → relay connected only while foregrounded. */
    private object ForegroundRelay : ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) {
                ScrapeRelay.start()
                // Coming to the foreground: check whether any books are due for an
                // auto-update (runs once the relay is up so gated sources work).
                AutoUpdate.trigger()
            }
        }
        override fun onActivityStopped(activity: Activity) {
            if (--started <= 0) { started = 0; ScrapeRelay.stop() }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    // Covers/inline images go through the same OkHttp client as the API, so they
    // carry the ns_session cookie (the cover endpoint requires auth).
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Net.client }
            .build()
}
