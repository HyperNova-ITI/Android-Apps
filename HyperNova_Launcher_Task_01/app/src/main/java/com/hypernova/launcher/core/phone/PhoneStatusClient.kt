package com.hypernova.launcher.core.phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.hypernova.launcher.core.state.AppConnectionState

/**
 * Observes the Android public Bluetooth state used by the Launcher Phone card.
 *
 * Bluetooth enabled alone never means that a phone is connected.
 *
 * For the standalone Launcher layer we mark the card connected only after
 * Android reports an actual Bluetooth connection to a device classified as
 * PHONE.
 *
 * Exact AAOS HFP Client readiness remains a platform integration boundary and
 * can replace this detector later without changing the Launcher UI contract.
 */
class PhoneStatusClient(
    context: Context,
    private val onSnapshotChanged: (PhoneSnapshot) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    private val bluetoothAdapter: BluetoothAdapter? =
        BluetoothAdapter.getDefaultAdapter()

    private var started = false
    private var connectedPhone: BluetoothDevice? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                    handleConnectionStateChanged(intent)
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (
                        intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR,
                        ) == BluetoothAdapter.STATE_OFF
                    ) {
                        connectedPhone = null
                    }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.bluetoothDevice()

                    if (
                        device != null &&
                        intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE,
                            BluetoothDevice.ERROR,
                        ) == BluetoothDevice.BOND_NONE &&
                        sameDevice(connectedPhone, device)
                    ) {
                        connectedPhone = null
                    }
                }
            }

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
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }

        /*
         * Bluetooth broadcasts can originate from the privileged Bluetooth
         * process rather than the system UID. RECEIVER_EXPORTED is therefore
         * required to receive those framework broadcasts reliably.
         */
        ContextCompat.registerReceiver(
            applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        applicationContext.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.BLUETOOTH_ON),
            false,
            bluetoothObserver,
        )

        refresh()
    }

    fun refresh() {
        if (!started) {
            return
        }

        val bluetoothEnabled = readBluetoothEnabled()

        if (bluetoothEnabled == false) {
            connectedPhone = null
        }

        val phone = connectedPhone?.takeIf {
            bluetoothEnabled == true && isPhoneDevice(it)
        }

        val phoneConnected = phone != null

        val connectionState =
            if (phoneConnected) {
                AppConnectionState.READY
            } else {
                AppConnectionState.DISCONNECTED
            }

        val phoneName = phone?.let {
            safeDeviceName(it)
        }

        Log.i(
            TAG,
            "Phone snapshot: bluetoothEnabled=$bluetoothEnabled, " +
                "phoneConnected=$phoneConnected, " +
                "phoneName=${phoneName ?: "none"}",
        )

        onSnapshotChanged(
            PhoneSnapshot(
                connectionState = connectionState,
                bluetoothEnabled = bluetoothEnabled,
                phoneConnected = phoneConnected,
                connectedPhoneName = phoneName,
            ),
        )
    }

    fun disconnect() {
        if (!started) {
            return
        }

        started = false

        try {
            applicationContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }

        applicationContext.contentResolver.unregisterContentObserver(
            bluetoothObserver,
        )

        connectedPhone = null
    }

    private fun handleConnectionStateChanged(intent: Intent) {
        val device = intent.bluetoothDevice() ?: return

        val state = intent.getIntExtra(
            BluetoothAdapter.EXTRA_CONNECTION_STATE,
            BluetoothAdapter.STATE_DISCONNECTED,
        )

        when (state) {
            BluetoothAdapter.STATE_CONNECTED -> {
                if (isPhoneDevice(device)) {
                    connectedPhone = device

                    Log.i(
                        TAG,
                        "Bluetooth phone connected: ${safeDeviceName(device)}",
                    )
                } else {
                    Log.i(
                        TAG,
                        "Ignoring connected non-phone Bluetooth device",
                    )
                }
            }

            BluetoothAdapter.STATE_DISCONNECTED -> {
                if (sameDevice(connectedPhone, device)) {
                    Log.i(
                        TAG,
                        "Bluetooth phone disconnected: ${safeDeviceName(device)}",
                    )

                    connectedPhone = null
                }
            }
        }
    }

    private fun readBluetoothEnabled(): Boolean? {
        return readValue("Bluetooth state") {
            when (
                Settings.Global.getInt(
                    applicationContext.contentResolver,
                    Settings.Global.BLUETOOTH_ON,
                    -1,
                )
            ) {
                0 -> false
                1 -> true
                else -> bluetoothAdapter?.isEnabled
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun isPhoneDevice(device: BluetoothDevice): Boolean {
        if (!hasBluetoothConnectPermission()) {
            Log.w(
                TAG,
                "BLUETOOTH_CONNECT is not granted; " +
                    "phone device classification unavailable",
            )

            return false
        }

        return try {
            device.bluetoothClass?.majorDeviceClass ==
                BluetoothClass.Device.Major.PHONE
        } catch (exception: SecurityException) {
            Log.w(
                TAG,
                "Could not inspect Bluetooth device class",
                exception,
            )

            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String {
        if (!hasBluetoothConnectPermission()) {
            return "Connected phone"
        }

        return try {
            device.name
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: "Connected phone"
        } catch (exception: SecurityException) {
            Log.w(
                TAG,
                "Could not read Bluetooth phone name",
                exception,
            )

            "Connected phone"
        }
    }

    @SuppressLint("MissingPermission")
    private fun sameDevice(
        first: BluetoothDevice?,
        second: BluetoothDevice?,
    ): Boolean {
        if (first == null || second == null) {
            return false
        }

        return try {
            first.address == second.address
        } catch (_: SecurityException) {
            first == second
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? {
        return getParcelableExtra(
            BluetoothDevice.EXTRA_DEVICE,
        )
    }

    private inline fun <T> readValue(
        label: String,
        block: () -> T?,
    ): T? {
        return try {
            block()
        } catch (exception: Exception) {
            Log.w(
                TAG,
                "Could not read $label",
                exception,
            )

            null
        }
    }

    companion object {
        private const val TAG = "PhoneStatusClient"
    }
}
