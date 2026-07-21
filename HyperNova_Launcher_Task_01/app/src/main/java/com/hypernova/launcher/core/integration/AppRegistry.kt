package com.hypernova.launcher.core.integration

import com.hypernova.launcher.R

/**
 * Central registry for all HyperNova applications.
 *
 * Package names, open actions and service class names must
 * remain centralized here.
 */
object AppRegistry {

    private val appSpecifications = mapOf(
        AppDestination.NOVA_AI to AppSpec(
            destination = AppDestination.NOVA_AI,
            packageName = "com.hypernova.ai",
            openAction = "com.hypernova.ai.action.OPEN",
            displayNameResourceId = R.string.app_name_nova_ai
        ),

        AppDestination.NAVIGATION to AppSpec(
            destination = AppDestination.NAVIGATION,
            packageName = "com.hypernova.navigation",
            openAction = "com.hypernova.navigation.action.OPEN",
            displayNameResourceId = R.string.app_name_navigation
        ),

        AppDestination.MEDIA to AppSpec(
            destination = AppDestination.MEDIA,
            packageName = "com.hypernova.media",
            openAction = "com.hypernova.media.action.OPEN",
            displayNameResourceId = R.string.app_name_media,

            // Contract expected from HyperNova Media.
            serviceClassName =
                "com.hypernova.media.playback.HyperNovaMediaSessionService"
        ),

        AppDestination.PHONE to AppSpec(
            destination = AppDestination.PHONE,
            packageName = "com.hypernova.phone",
            openAction = "com.hypernova.phone.action.OPEN",
            displayNameResourceId = R.string.app_name_phone
        ),

        AppDestination.CLIMATE to AppSpec(
            destination = AppDestination.CLIMATE,
            packageName = "com.hypernova.climate",
            openAction = "com.hypernova.climate.action.OPEN",
            displayNameResourceId = R.string.app_name_climate
        ),

        AppDestination.WEATHER to AppSpec(
            destination = AppDestination.WEATHER,
            packageName = "com.hypernova.weather",
            openAction = "com.hypernova.weather.action.OPEN",
            displayNameResourceId = R.string.app_name_weather
        ),

        AppDestination.DRIVER_PROFILE to AppSpec(
            destination = AppDestination.DRIVER_PROFILE,
            packageName = "com.hypernova.driverprofile",
            openAction = "com.hypernova.driverprofile.action.OPEN",
            displayNameResourceId =
                R.string.app_name_driver_profile
        ),

        AppDestination.SETTINGS to AppSpec(
            destination = AppDestination.SETTINGS,
            packageName = "com.hypernova.settings",
            openAction = "com.hypernova.settings.action.OPEN",
            displayNameResourceId = R.string.app_name_settings
        )
    )

    /**
     * Return the specification of one HyperNova application.
     */
    fun get(destination: AppDestination): AppSpec {
        return requireNotNull(
            appSpecifications[destination]
        ) {
            "No AppSpec registered for $destination"
        }
    }

    /**
     * Return every registered HyperNova application.
     */
    fun getAll(): List<AppSpec> {
        return appSpecifications.values.toList()
    }
}