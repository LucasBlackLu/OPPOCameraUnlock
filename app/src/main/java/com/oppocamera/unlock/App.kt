package com.oppocamera.unlock

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Link.start()
    }
}
