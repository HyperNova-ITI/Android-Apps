package com.hypernova.launcher.core.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Watches Android package changes for registered HyperNova applications.
 *
 * Lifecycle ownership stays with MainActivity: [start] is paired with [stop].
 * A lifecycle refresh still runs on resume, so packages changed while the
 * launcher was stopped are also detected without polling.
 */
class AppAvailabilityMonitor(
    context: Context,
    private val onPackageChanged: (String) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val registeredPackages =
        AppRegistry.getAll().mapTo(mutableSetOf()) { it.packageName }
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val packageName = intent?.data?.schemeSpecificPart ?: return
            if (packageName in registeredPackages) {
                onPackageChanged(packageName)
            }
        }
    }

    fun start() {
        if (registered) return

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        ContextCompat.registerReceiver(
            applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    fun stop() {
        if (!registered) return
        applicationContext.unregisterReceiver(receiver)
        registered = false
    }
}
