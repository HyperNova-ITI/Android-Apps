package com.hypernova.navigation

import android.app.Application
import com.hypernova.navigation.core.GoogleApiKeyPolicy

class HyperNovaNavigationApplication : Application() {
    lateinit var navigationRuntime: NavigationRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        val apiKey = BuildConfig.MAPS_API_KEY
        val configured = GoogleApiKeyPolicy.isConfigured(apiKey)
        navigationRuntime = NavigationRuntime.create(this, apiKey, configured)
    }
}
