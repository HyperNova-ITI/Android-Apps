package com.hypernova.launcher.core.theme

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log

/**
 * Controls the HyperNova Launcher day and night appearance.
 *
 * Development installation:
 * - The launcher is installed as a normal APK.
 * - The button changes this application using setApplicationNightMode().
 *
 * Production AOSP installation:
 * - The launcher is installed as a privileged system application.
 * - MODIFY_DAY_NIGHT_MODE is granted through a privapp allowlist.
 * - The same button changes the Android system mode using setNightMode().
 */
class LauncherThemeController(context: Context) {

    companion object {
        private const val TAG = "LauncherThemeController"

        private const val MODIFY_DAY_NIGHT_MODE_PERMISSION =
            "android.permission.MODIFY_DAY_NIGHT_MODE"
    }

    private val applicationContext =
        context.applicationContext

    private val uiModeManager =
        checkNotNull(
            applicationContext.getSystemService(
                UiModeManager::class.java
            )
        ) {
            "UiModeManager is not available"
        }

    /**
     * Return true when the currently applied resources are night resources.
     */
    fun isNightModeActive(): Boolean {
        val nightMode =
            applicationContext.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK

        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Switch between Light and Dark mode.
     *
     * A privileged production build changes the complete Android system.
     * A normal development APK changes the launcher application only.
     */
    fun toggleTheme(): ThemeChangeScope {
        val requestedMode =
            if (isNightModeActive()) {
                UiModeManager.MODE_NIGHT_NO
            } else {
                UiModeManager.MODE_NIGHT_YES
            }

        return if (canModifySystemNightMode()) {
            changeSystemNightModeOrFallback(requestedMode)
        } else {
            changeApplicationNightMode(requestedMode)
            ThemeChangeScope.APPLICATION
        }
    }

    /**
     * Check whether Android granted the privileged system permission.
     */
    private fun canModifySystemNightMode(): Boolean {
        return applicationContext.checkSelfPermission(
            MODIFY_DAY_NIGHT_MODE_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Try the production system-wide path.
     * Fall back safely when the platform rejects the request.
     */
    private fun changeSystemNightModeOrFallback(
        requestedMode: Int
    ): ThemeChangeScope {
        return try {
            uiModeManager.setNightMode(requestedMode)
            ThemeChangeScope.SYSTEM
        } catch (exception: SecurityException) {
            Log.w(
                TAG,
                "System night mode permission was rejected. " +
                    "Using application night mode.",
                exception
            )

            changeApplicationNightMode(requestedMode)
            ThemeChangeScope.APPLICATION
        } catch (exception: RuntimeException) {
            Log.e(
                TAG,
                "System night mode change failed. " +
                    "Using application night mode.",
                exception
            )

            changeApplicationNightMode(requestedMode)
            ThemeChangeScope.APPLICATION
        }
    }

    /**
     * Change only the HyperNova Launcher appearance.
     *
     * This public API does not require privileged system permissions.
     * Android recreates the activity and loads values or values-night.
     */
    private fun changeApplicationNightMode(
        requestedMode: Int
    ) {
        uiModeManager.setApplicationNightMode(requestedMode)
    }
}

/**
 * Describes whether the last theme request affected the application
 * or the complete Android system.
 */
enum class ThemeChangeScope {
    APPLICATION,
    SYSTEM
}
