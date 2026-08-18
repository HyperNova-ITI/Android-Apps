package com.hypernova.launcher.core.integration

/**
 * Result returned after trying to open a HyperNova application.
 */
sealed class AppLaunchResult {

    data class Launched(
        val destination: AppDestination
    ) : AppLaunchResult()

    data class NotInstalled(
        val destination: AppDestination
    ) : AppLaunchResult()

    data class NoLaunchableActivity(
        val destination: AppDestination
    ) : AppLaunchResult()

    data class Failed(
        val destination: AppDestination,
        val cause: Throwable
    ) : AppLaunchResult()
}