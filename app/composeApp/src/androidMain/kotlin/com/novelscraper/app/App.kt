package com.novelscraper.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.net.AutoUpdate
import com.novelscraper.app.net.Net
import com.novelscraper.app.net.NuResolver
import com.novelscraper.app.net.ScrapeRelay
import com.novelscraper.app.platform.initPlatform
import com.novelscraper.app.tts.AndroidTtsPlayer
import com.novelscraper.app.tts.TtsController
import com.novelscraper.app.ui.theme.ThemeController
import kotlin.time.ExperimentalTime

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        initPlatform(this)  // first: the shared code's settings/files/toasts need it
        Net.init()
        ReaderPrefs.init()
        ThemeController.init()
        TtsController.player = AndroidTtsPlayer(this)
        ScrapeRelay.init(this)  // lets the relay create its offscreen render WebView
        NuResolver.init(this)   // offscreen NovelUpdates reader (no visible browser when logged in)
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
    // carry the ns_session cookie (the cover endpoint requires auth), and follow
    // the server's cache headers so a replaced cover shows up.
    @OptIn(ExperimentalCoilApi::class, ExperimentalTime::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { Net.client },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    ),
                )
            }
            .build()
}
