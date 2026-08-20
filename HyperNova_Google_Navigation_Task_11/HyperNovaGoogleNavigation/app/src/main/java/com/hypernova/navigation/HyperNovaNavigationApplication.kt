package com.hypernova.navigation

import android.app.Application
import com.google.android.libraries.navigation.NavigationApi
import com.hypernova.navigation.core.GoogleApiKeyPolicy

class HyperNovaNavigationApplication : Application() {
    lateinit var navigationRuntime: NavigationRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        val apiKey = BuildConfig.MAPS_API_KEY
        val configured = GoogleApiKeyPolicy.isConfigured(apiKey)
        if (configured) NavigationApi.setApiKey(apiKey)
        navigationRuntime = NavigationRuntime.create(this, apiKey, configured)
    }
}
