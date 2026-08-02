package com.hypernova.launcher.core.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.hypernova.launcher.core.state.AppConnectionState

/**
 * Observes only non-privileged Bluetooth enabled state for the Phone card.
 *
 * Bluetooth enabled does not prove that a phone is connected, and this client
 * deliberately makes no call, account, device, or identity claims.
 */
class PhoneStatusClient(
    context: Context,
    private val onSnapshotChanged: (PhoneSnapshot) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    private val bluetoothObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            refresh()
        }
    }

    fun connect() {
        if (started) {
            refresh()
            return
        }
        started = true
        val filter = IntentFilter().apply {
            addAction(BLUETOOTH_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        applicationContext.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.BLUETOOTH_ON),
            false,
            bluetoothObserver,
        )
        refresh()
    }

    fun refresh() {
        if (!started) return
        val bluetoothEnabled = readValue("Bluetooth state") {
            when (
                Settings.Global.getInt(
                    applicationContext.contentResolver,
                    Settings.Global.BLUETOOTH_ON,
                    -1,
                )
            ) {
                0 -> false
                1 -> true
                else -> null
            }
        }
        onSnapshotChanged(
            PhoneSnapshot(
                // There is no exported Phone status service. Keep runtime
                // connection separate from package availability.
                connectionState = AppConnectionState.DISCONNECTED,
                bluetoothEnabled = bluetoothEnabled,
            ),
        )
    }

    fun disconnect() {
        if (!started) return
        started = false
        try {
            applicationContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // The receiver was already released.
        }
        applicationContext.contentResolver.unregisterContentObserver(bluetoothObserver)
    }

    private inline fun <T> readValue(label: String, block: () -> T?): T? {
        return try {
            block()
        } catch (exception: Exception) {
            Log.w(TAG, "Could not read $label", exception)
            null
        }
    }

    companion object {
        private const val TAG = "PhoneStatusClient"
        private const val BLUETOOTH_STATE_CHANGED_ACTION =
            "android.bluetooth.adapter.action.STATE_CHANGED"
    }
}
