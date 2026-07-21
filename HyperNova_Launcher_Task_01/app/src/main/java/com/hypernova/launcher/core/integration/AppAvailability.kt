package com.hypernova.launcher.core.integration

/**
 * Describes whether an application can currently be opened.
 */
enum class AppAvailability {

    /**
     * The package exists and Android found an Activity that can open it.
     */
    AVAILABLE,

    /**
     * The application package is not installed.
     */
    NOT_INSTALLED,

    /**
     * The package exists, but it does not expose a launchable Activity.
     */
    NO_LAUNCHABLE_ACTIVITY
}