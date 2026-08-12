package com.novelscraper.app

import android.app.Application
import com.novelscraper.app.net.Net

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Net.init(this)
    }
}
