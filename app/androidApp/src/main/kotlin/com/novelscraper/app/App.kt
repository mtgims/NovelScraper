package com.novelscraper.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.novelscraper.app.data.LibraryPrefs
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.update.AppUpdates
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.library.DownloadKeeper
import com.novelscraper.app.library.Library
import com.novelscraper.app.net.Account
import com.novelscraper.app.net.Net
import com.novelscraper.app.net.buildImageLoader
import com.novelscraper.app.platform.initPlatform
import com.novelscraper.app.tts.AndroidTtsPlayer
import com.novelscraper.app.tts.TtsController
import com.novelscraper.app.ui.theme.ThemeController

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        initPlatform(this, BuildConfig.DEBUG)  // first: the shared code's settings/files/toasts need it
        Net.init()
        Account.init()
        Extensions.init(Net.client)
        AppUpdates.init(Net.client)
        // A quiet look at the releases page on startup: a reader who didn't
        // ask isn't told that GitHub was unreachable.
        AppUpdates.check(quietly = true)
        Library.init()
        // Chapter downloads keep going in the background under a service.
        DownloadKeeper.start()
        ReaderPrefs.init()
        LibraryPrefs.init()
        ThemeController.init()
        TtsController.player = AndroidTtsPlayer(this)
        // Sync while the app is foregrounded, and send what changed on the way out.
        registerActivityLifecycleCallbacks(ForegroundSync)
    }

    /** Ref-counts started activities, so syncing runs only while foregrounded. */
    private object ForegroundSync : ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) Account.onForeground()
        }
        override fun onActivityStopped(activity: Activity) {
            if (--started <= 0) { started = 0; Account.onBackground() }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = buildImageLoader(context)
}
