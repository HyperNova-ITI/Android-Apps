package com.hypernova.wdt

import android.app.Application

class HyperNovaWdtApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeModeController.applySavedMode(this)
    }
}
