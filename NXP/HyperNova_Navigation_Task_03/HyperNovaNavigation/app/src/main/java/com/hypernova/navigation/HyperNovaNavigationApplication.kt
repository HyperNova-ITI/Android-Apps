package com.hypernova.navigation

import android.app.Application
import com.hypernova.navigation.data.persistence.NavigationPreferences
import com.hypernova.navigation.domain.repository.NavigationRepository
import com.hypernova.navigation.domain.simulation.SimulatedLocationSource

class HyperNovaNavigationApplication : Application() {
    lateinit var navigationPreferences: NavigationPreferences
        private set

    lateinit var navigationRepository: NavigationRepository
        private set

    override fun onCreate() {
        super.onCreate()

        navigationPreferences =
            NavigationPreferences(applicationContext).also {
                it.retireLocalTheme()
            }
        navigationRepository =
            NavigationRepository(
                preferences = navigationPreferences,
                locationSource = SimulatedLocationSource()
            )
    }

    override fun onTerminate() {
        navigationRepository.close()
        super.onTerminate()
    }
}
