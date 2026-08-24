package com.hypernova.navigation

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.hypernova.navigation.core.GoogleApiKeyPolicy

class HyperNovaNavigationApplication : Application() {
    lateinit var navigationRuntime: NavigationRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val apiKey = BuildConfig.MAPS_API_KEY
        val configured = GoogleApiKeyPolicy.isConfigured(apiKey)
        navigationRuntime = NavigationRuntime.create(this, apiKey, configured)
    }
}
