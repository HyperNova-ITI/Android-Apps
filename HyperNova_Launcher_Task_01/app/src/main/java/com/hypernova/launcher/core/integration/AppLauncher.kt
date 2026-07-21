package com.hypernova.launcher.core.integration

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Checks and opens registered HyperNova applications.
 *
 * UI classes must not create application Intents directly.
 */
class AppLauncher(context: Context) {

    /**
     * Use the application context so this class does not keep
     * an Activity instance alive.
     */
    private val applicationContext =
        context.applicationContext

    private val packageManager: PackageManager
        get() = applicationContext.packageManager

    /**
     * Check whether a registered HyperNova application
     * is installed and can be opened.
     */
    fun getAvailability(
        destination: AppDestination
    ): AppAvailability {
        val appSpec =
            AppRegistry.get(destination)

        val openIntent =
            createOpenIntent(appSpec)

        if (openIntent != null) {
            return AppAvailability.AVAILABLE
        }

        return if (
            isPackageInstalled(appSpec.packageName)
        ) {
            AppAvailability.NO_LAUNCHABLE_ACTIVITY
        } else {
            AppAvailability.NOT_INSTALLED
        }
    }

    /**
     * Try to open one registered HyperNova application.
     */
    fun open(
        destination: AppDestination
    ): AppLaunchResult {
        val appSpec =
            AppRegistry.get(destination)

        val openIntent =
            createOpenIntent(appSpec)

        if (openIntent == null) {
            return if (
                isPackageInstalled(appSpec.packageName)
            ) {
                AppLaunchResult.NoLaunchableActivity(
                    destination
                )
            } else {
                AppLaunchResult.NotInstalled(
                    destination
                )
            }
        }

        return try {
            applicationContext.startActivity(
                openIntent
            )

            AppLaunchResult.Launched(
                destination
            )
        } catch (
            exception: ActivityNotFoundException
        ) {
            AppLaunchResult.NoLaunchableActivity(
                destination
            )
        } catch (
            exception: SecurityException
        ) {
            AppLaunchResult.Failed(
                destination,
                exception
            )
        } catch (
            exception: Exception
        ) {
            AppLaunchResult.Failed(
                destination,
                exception
            )
        }
    }

    /**
     * Create an Intent for one HyperNova application.
     *
     * Opening order:
     *
     * 1. Stable HyperNova OPEN action.
     * 2. Normal launcher Activity.
     */
    private fun createOpenIntent(
        appSpec: AppSpec
    ): Intent? {
        val actionIntent =
            createActionIntent(appSpec)

        val resolvedAction =
            packageManager.resolveActivity(
                actionIntent,
                PackageManager.ResolveInfoFlags.of(
                    PackageManager
                        .MATCH_DEFAULT_ONLY
                        .toLong()
                )
            )

        val resolvedActivityInfo =
            resolvedAction?.activityInfo

        if (
            resolvedActivityInfo != null &&
            resolvedActivityInfo.packageName ==
            appSpec.packageName
        ) {
            /*
             * Make the resolved Intent explicit.
             *
             * This guarantees that the selected HyperNova
             * Activity receives the action.
             */
            val component =
                ComponentName(
                    resolvedActivityInfo.packageName,
                    resolvedActivityInfo.name
                )

            return prepareActivityIntent(
                actionIntent.apply {
                    this.component = component
                }
            )
        }

        /*
         * The target application may not expose the custom
         * OPEN action yet.
         *
         * Fall back to its normal launcher Activity.
         */
        val launcherIntent =
            packageManager.getLaunchIntentForPackage(
                appSpec.packageName
            )

        return launcherIntent?.let {
            prepareActivityIntent(it)
        }
    }

    /**
     * Create the stable public HyperNova action Intent.
     */
    private fun createActionIntent(
        appSpec: AppSpec
    ): Intent {
        return Intent(
            appSpec.openAction
        ).apply {
            setPackage(
                appSpec.packageName
            )

            addCategory(
                Intent.CATEGORY_DEFAULT
            )
        }
    }

    /**
     * Add the common flags used when opening applications
     * from the launcher.
     */
    private fun prepareActivityIntent(
        intent: Intent
    ): Intent {
        return intent.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

            addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            )

            addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    }

    /**
     * Check whether Android knows the target package.
     */
    private fun isPackageInstalled(
        packageName: String
    ): Boolean {
        return try {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(
                    0L
                )
            )

            true
        } catch (
            exception:
            PackageManager.NameNotFoundException
        ) {
            false
        }
    }
}