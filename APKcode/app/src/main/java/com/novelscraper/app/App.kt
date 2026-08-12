package com.novelscraper.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.net.Net
import com.novelscraper.app.ui.theme.ThemeController

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Net.init(this)
        ReaderPrefs.init(this)
        ThemeController.init(this)
    }

    // Covers/inline images go through the same OkHttp client as the API, so they
    // carry the ns_session cookie (the cover endpoint requires auth).
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Net.client }
            .build()
}
