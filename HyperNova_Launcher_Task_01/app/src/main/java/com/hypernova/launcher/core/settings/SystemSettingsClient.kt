package com.hypernova.launcher.core.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.hypernova.launcher.core.state.AppConnectionState
import kotlin.math.roundToInt

/** Observes Settings card values through read-only Android framework APIs. */
class SystemSettingsClient(
    context: Context,
    private val onSnapshotChanged: (SystemSettingsSnapshot) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    private val settingsObserver = object : ContentObserver(mainHandler) {
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
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BLUETOOTH_STATE_CHANGED_ACTION)
            addAction(VOLUME_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val resolver = applicationContext.contentResolver
        resolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            settingsObserver,
        )
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.BLUETOOTH_ON),
            false,
            settingsObserver,
        )
        refresh()
    }

    fun refresh() {
        if (!started) return
        val wifi = readValue("Wi-Fi") { wifiManager?.isWifiEnabled }
        val bluetooth = readValue("Bluetooth") {
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
        val brightness = readValue("brightness") {
            Settings.System.getInt(
                applicationContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            ).coerceIn(0, MAX_BRIGHTNESS)
                .toFloat()
                .div(MAX_BRIGHTNESS)
                .times(100f)
                .roundToInt()
        }
        val volume = readValue("media volume") {
            val manager = audioManager ?: return@readValue null
            val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maximum <= 0) return@readValue null
            (manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum * 100f)
                .roundToInt()
                .coerceIn(0, 100)
        }

        val hasState = listOf(wifi, bluetooth, brightness, volume).any { it != null }
        onSnapshotChanged(
            SystemSettingsSnapshot(
                connectionState = if (hasState) AppConnectionState.READY else AppConnectionState.ERROR,
                wifiEnabled = wifi,
                bluetoothEnabled = bluetooth,
                brightnessPercent = brightness,
                mediaVolumePercent = volume,
                errorMessage = if (hasState) null else "System settings unavailable",
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
        applicationContext.contentResolver.unregisterContentObserver(settingsObserver)
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
        private const val TAG = "SystemSettingsClient"
        private const val MAX_BRIGHTNESS = 255
        private const val BLUETOOTH_STATE_CHANGED_ACTION =
            "android.bluetooth.adapter.action.STATE_CHANGED"
        private const val VOLUME_CHANGED_ACTION =
            "android.media.VOLUME_CHANGED_ACTION"
    }
}
