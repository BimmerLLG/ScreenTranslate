package com.screentranslate

import android.app.Application
import com.screentranslate.logger.L

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        L.init(this)
        L.d("App", "Application created")
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
