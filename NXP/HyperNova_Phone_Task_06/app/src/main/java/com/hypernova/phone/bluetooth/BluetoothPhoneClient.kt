package com.hypernova.phone.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hypernova.connectivity.IHyperNovaConnectivityCallback
import com.hypernova.connectivity.IHyperNovaConnectivityService
import com.hypernova.phone.domain.BluetoothConnectionState
import com.hypernova.phone.domain.BluetoothDeviceInfo
import com.hypernova.phone.domain.BluetoothUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * HyperNova Phone Bluetooth/HFP state provider.
 *
 * Runtime strategy:
 *
 * HyperNova AAOS / RPi:
 *     HyperNovaConnectivityService remains the authoritative source for
 *     Bluetooth HEADSET_CLIENT / HFP state.
 *
 * NXP Standard Android:
 *     The privileged HyperNova connectivity bridge is not installed.
 *     Android Bluetooth registers the connected mobile phone in Telecom
 *     through:
 *
 *       com.android.bluetooth.hfpclient.HfpClientConnectionService
 *
 *     A call-capable PhoneAccount owned by that ConnectionService is
 *     therefore used as authoritative evidence that an HFP phone is
 *     connected.
 *
 * Generic Bluetooth bonding alone is never treated as HFP connection.
 */
class BluetoothPhoneClient(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val adapter:
        BluetoothAdapter? =
        BluetoothAdapter.getDefaultAdapter()

    private val refreshHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val delayedTelecomRefresh =
        Runnable {
            publishSnapshot()
        }

    private val _state =
        MutableStateFlow(
            buildSnapshot()
        )

    val state:
        StateFlow<BluetoothUiState> =
        _state.asStateFlow()

    private var receiverRegistered =
        false

    private var platformBindRequested =
        false

    private var platformService:
        IHyperNovaConnectivityService? =
        null

    private var platformHfpState:
        PlatformHfpState? =
        null

    private var connectionHint:
        Int? =
        null

    private val bluetoothReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context,
                intent: Intent
            ) {

                if (
                    intent.action ==
                    BluetoothAdapter
                        .ACTION_CONNECTION_STATE_CHANGED
                ) {

                    connectionHint =
                        intent.getIntExtra(
                            BluetoothAdapter
                                .EXTRA_CONNECTION_STATE,
                            BluetoothAdapter
                                .STATE_DISCONNECTED
                        )
                }

                refreshPlatformState(
                    requestRemoteRefresh = false
                )

                publishSnapshot()

                /*
                 * The Bluetooth framework may register/unregister the
                 * HFP PhoneAccount slightly after the generic Bluetooth
                 * connection broadcast. Retry after the profile stack
                 * has had time to settle.
                 */
                scheduleTelecomRefresh()
            }
        }

    private val platformCallback =
        object :
            IHyperNovaConnectivityCallback.Stub() {

            override fun onWifiStateChanged(
                enabled: Boolean
            ) = Unit

            override fun onWifiScanStateChanged(
                scanning: Boolean
            ) = Unit

            override fun onWifiNetworksCleared() =
                Unit

            override fun onWifiNetworkFound(
                ssid: String?,
                bssid: String?,
                signalLevel: Int,
                securityType: Int,
                saved: Boolean,
                connected: Boolean
            ) = Unit

            override fun onWifiScanCompleted() =
                Unit

            override fun onWifiConnectionStateChanged(
                ssid: String?,
                state: Int,
                failureReason: Int
            ) = Unit

            override fun onConnectedWifiChanged(
                ssid: String?,
                bssid: String?,
                ipAddress: String?,
                signalLevel: Int
            ) = Unit

            override fun onPhoneConnectionChanged(
                connected: Boolean,
                deviceName: String?,
                deviceAddress: String?
            ) {

                platformHfpState =
                    PlatformHfpState(
                        connected =
                            connected,

                        deviceName =
                            deviceName.orEmpty(),

                        deviceAddress =
                            deviceAddress.orEmpty()
                    )

                Log.i(
                    TAG,
                    "AAOS HFP callback: " +
                        "connected=$connected " +
                        "name=${deviceName.orEmpty()}"
                )

                publishSnapshot()
            }
        }

    private val platformConnection =
        object : ServiceConnection {

            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?
            ) {

                val service =
                    IHyperNovaConnectivityService
                        .Stub
                        .asInterface(
                            binder
                        )

                platformService =
                    service

                Log.i(
                    TAG,
                    "HyperNovaConnectivityService connected"
                )

                try {

                    val version =
                        service
                            .getContractVersion()

                    Log.i(
                        TAG,
                        "Connectivity contract version=$version"
                    )

                    if (
                        version <
                        REQUIRED_PLATFORM_CONTRACT_VERSION
                    ) {

                        Log.e(
                            TAG,
                            "Connectivity contract is too old"
                        )

                        platformHfpState =
                            null

                        publishSnapshot()
                        return
                    }

                    service.registerCallback(
                        platformCallback
                    )

                    refreshPlatformState(
                        requestRemoteRefresh = true
                    )

                } catch (
                    exception: RemoteException
                ) {

                    Log.e(
                        TAG,
                        "Unable to initialize AAOS connectivity bridge",
                        exception
                    )

                    platformHfpState =
                        null

                    publishSnapshot()
                }
            }

            override fun onServiceDisconnected(
                name: ComponentName?
            ) {

                Log.w(
                    TAG,
                    "HyperNovaConnectivityService disconnected"
                )

                platformService =
                    null

                platformHfpState =
                    null

                publishSnapshot()
                scheduleTelecomRefresh()
            }

            override fun onBindingDied(
                name: ComponentName?
            ) {

                Log.e(
                    TAG,
                    "HyperNovaConnectivityService binding died"
                )

                platformService =
                    null

                platformHfpState =
                    null

                platformBindRequested =
                    false

                publishSnapshot()
                scheduleTelecomRefresh()
            }

            override fun onNullBinding(
                name: ComponentName?
            ) {

                Log.e(
                    TAG,
                    "HyperNovaConnectivityService returned null binding"
                )

                platformService =
                    null

                platformHfpState =
                    null

                platformBindRequested =
                    false

                publishSnapshot()
                scheduleTelecomRefresh()
            }
        }

    fun start() {

        if (
            !receiverRegistered
        ) {

            val filter =
                IntentFilter().apply {

                    addAction(
                        BluetoothAdapter
                            .ACTION_STATE_CHANGED
                    )

                    addAction(
                        BluetoothAdapter
                            .ACTION_CONNECTION_STATE_CHANGED
                    )

                    addAction(
                        BluetoothDevice
                            .ACTION_BOND_STATE_CHANGED
                    )
                }

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                appContext.registerReceiver(
                    bluetoothReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )

            } else {

                @Suppress("DEPRECATION")
                appContext.registerReceiver(
                    bluetoothReceiver,
                    filter
                )
            }

            receiverRegistered =
                true
        }

        bindPlatformService()

        publishSnapshot()

        /*
         * Useful on NXP when the HFP PhoneAccount is created shortly
         * after Bluetooth profile initialization.
         */
        scheduleTelecomRefresh()
    }

    fun stop() {

        refreshHandler.removeCallbacks(
            delayedTelecomRefresh
        )

        if (
            receiverRegistered
        ) {

            try {

                appContext.unregisterReceiver(
                    bluetoothReceiver
                )

            } catch (
                exception: RuntimeException
            ) {

                Log.w(
                    TAG,
                    "Bluetooth receiver already unregistered",
                    exception
                )
            }

            receiverRegistered =
                false
        }

        val service =
            platformService

        if (
            service != null
        ) {

            try {

                service.unregisterCallback(
                    platformCallback
                )

            } catch (
                exception: RemoteException
            ) {

                Log.w(
                    TAG,
                    "Unable to unregister connectivity callback",
                    exception
                )
            }
        }

        if (
            platformBindRequested
        ) {

            try {

                appContext.unbindService(
                    platformConnection
                )

            } catch (
                exception: RuntimeException
            ) {

                Log.w(
                    TAG,
                    "Connectivity service already unbound",
                    exception
                )
            }
        }

        platformBindRequested =
            false

        platformService =
            null

        platformHfpState =
            null
    }

    fun refresh() {

        if (
            platformService == null
        ) {

            bindPlatformService()
        }

        refreshPlatformState(
            requestRemoteRefresh = true
        )

        publishSnapshot()
        scheduleTelecomRefresh()
    }

    fun hasConnectPermission():
        Boolean =

        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT
        ) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasReadPhoneStatePermission():
        Boolean =

        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE
        ) ==
            PackageManager.PERMISSION_GRANTED

    private fun scheduleTelecomRefresh() {

        refreshHandler.removeCallbacks(
            delayedTelecomRefresh
        )

        refreshHandler.postDelayed(
            delayedTelecomRefresh,
            TELECOM_REFRESH_DELAY_SHORT_MS
        )

        refreshHandler.postDelayed(
            delayedTelecomRefresh,
            TELECOM_REFRESH_DELAY_LONG_MS
        )
    }

    private fun bindPlatformService() {

        if (
            platformBindRequested ||
            platformService != null
        ) {

            return
        }

        val intent =
            Intent(
                PLATFORM_BIND_ACTION
            ).apply {

                setPackage(
                    PLATFORM_PACKAGE
                )
            }

        try {

            platformBindRequested =
                appContext.bindService(
                    intent,
                    platformConnection,
                    Context.BIND_AUTO_CREATE
                )

            Log.i(
                TAG,
                "AAOS connectivity bind requested=" +
                    platformBindRequested
            )

        } catch (
            exception: SecurityException
        ) {

            /*
             * Expected for the NXP standalone APK and other builds
             * without access to the platform AAOS bridge.
             */
            Log.i(
                TAG,
                "AAOS connectivity bridge unavailable to this build"
            )

            platformBindRequested =
                false

        } catch (
            exception: RuntimeException
        ) {

            Log.w(
                TAG,
                "AAOS connectivity service is unavailable",
                exception
            )

            platformBindRequested =
                false
        }
    }

    private fun refreshPlatformState(
        requestRemoteRefresh: Boolean
    ) {

        val service =
            platformService
                ?: return

        try {

            if (
                service.getContractVersion() <
                REQUIRED_PLATFORM_CONTRACT_VERSION
            ) {

                platformHfpState =
                    null

                return
            }

            val connected =
                service.isHfpConnected()

            val name =
                service
                    .getHfpDeviceName()
                    .orEmpty()

            val address =
                service
                    .getHfpDeviceAddress()
                    .orEmpty()

            platformHfpState =
                PlatformHfpState(
                    connected =
                        connected,

                    deviceName =
                        name,

                    deviceAddress =
                        address
                )

            Log.i(
                TAG,
                "AAOS HFP snapshot: " +
                    "connected=$connected " +
                    "name=$name"
            )

            if (
                requestRemoteRefresh
            ) {

                service.refreshState()
            }

        } catch (
            exception: RemoteException
        ) {

            Log.e(
                TAG,
                "Unable to read AAOS HFP state",
                exception
            )

            platformService =
                null

            platformHfpState =
                null
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildSnapshot():
        BluetoothUiState {

        val localAdapter =
            adapter
                ?: return BluetoothUiState(
                    state =
                        BluetoothConnectionState
                            .CONNECTION_FAILED,

                    detail =
                        "Bluetooth hardware is unavailable"
                )

        if (
            !localAdapter.isEnabled
        ) {

            return BluetoothUiState(
                state =
                    BluetoothConnectionState
                        .BLUETOOTH_DISABLED,

                detail =
                    "Turn on Bluetooth to connect a phone"
            )
        }

        if (
            !hasConnectPermission()
        ) {

            return BluetoothUiState(
                state =
                    BluetoothConnectionState
                        .DEVICE_LIST,

                detail =
                    "Bluetooth access is required to view paired devices"
            )
        }

        val bondedDevices =
            localAdapter
                .bondedDevices
                .orEmpty()

        val paired =
            bondedDevices
                .map {
                    device ->
                    device.toInfo()
                }
                .sortedBy {
                    it.name.lowercase()
                }

        /*
         * AAOS / RPi authoritative HFP path.
         */
        val hfp =
            platformHfpState

        if (
            hfp?.connected == true
        ) {

            val name =
                hfp.deviceName
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?: "Connected phone"

            return connectedSnapshot(
                paired =
                    paired,

                name =
                    name,

                source =
                    "AAOS"
            )
        }

        /*
         * If the AAOS bridge is present and explicitly reports HFP
         * disconnected, trust it.
         */
        if (
            platformService != null
        ) {

            return BluetoothUiState(
                state =
                    BluetoothConnectionState
                        .DEVICE_LIST,

                pairedDevices =
                    paired,

                connectedDeviceName =
                    null,

                detail =
                    "No phone connected for hands-free calling"
            )
        }

        /*
         * NXP Standard Android fallback.
         *
         * A call-capable PhoneAccount owned by the framework Bluetooth
         * HFP Client ConnectionService is authoritative proof that the
         * Android guest currently has an HFP telephone endpoint.
         */
        val telecomHfp =
            readNxpTelecomHfpState(
                bondedDevices
            )

        if (
            telecomHfp != null
        ) {

            return connectedSnapshot(
                paired =
                    paired,

                name =
                    telecomHfp.deviceName,

                source =
                    "NXP-Telecom"
            )
        }

        /*
         * Generic Bluetooth connection is intentionally NOT treated as
         * HFP. A paired speaker/headset must not make Phone appear
         * connected.
         */
        val publicState =
            when (
                connectionHint
            ) {

                BluetoothAdapter.STATE_CONNECTING ->
                    BluetoothConnectionState
                        .CONNECTING

                BluetoothAdapter.STATE_DISCONNECTING ->
                    BluetoothConnectionState
                        .DISCONNECTED

                else ->
                    BluetoothConnectionState
                        .DEVICE_LIST
            }

        return BluetoothUiState(
            state =
                publicState,

            pairedDevices =
                paired,

            connectedDeviceName =
                null,

            detail =
                if (
                    paired.isEmpty()
                ) {

                    "No paired Bluetooth devices"

                } else {

                    "Paired devices available"
                }
        )
    }

    private fun connectedSnapshot(
        paired: List<BluetoothDeviceInfo>,
        name: String,
        source: String
    ): BluetoothUiState {

        Log.i(
            TAG,
            "HFP phone connected via $source: $name"
        )

        return BluetoothUiState(
            state =
                BluetoothConnectionState
                    .CONNECTED,

            pairedDevices =
                paired,

            connectedDeviceName =
                name,

            detail =
                "Hands-free calling connected"
        )
    }

    @SuppressLint("MissingPermission")
    private fun readNxpTelecomHfpState(
        bondedDevices: Set<BluetoothDevice>
    ): TelecomHfpState? {

        if (
            !hasReadPhoneStatePermission()
        ) {

            Log.d(
                TAG,
                "NXP Telecom HFP detection waiting for READ_PHONE_STATE"
            )

            return null
        }

        val telecomManager =
            appContext.getSystemService(
                TelecomManager::class.java
            )
                ?: return null

        val account =
            try {

                val defaultAccount =
                    telecomManager
                        .getDefaultOutgoingPhoneAccount(
                            TEL_SCHEME
                        )

                if (
                    defaultAccount != null &&
                    defaultAccount.isBluetoothHfpClientAccount()
                ) {

                    defaultAccount

                } else {

                    telecomManager
                        .callCapablePhoneAccounts
                        .firstOrNull {
                            candidate ->
                            candidate
                                .isBluetoothHfpClientAccount()
                        }
                }

            } catch (
                exception: SecurityException
            ) {

                Log.w(
                    TAG,
                    "READ_PHONE_STATE rejected while reading Telecom HFP accounts",
                    exception
                )

                return null

            } catch (
                exception: RuntimeException
            ) {

                Log.w(
                    TAG,
                    "Unable to inspect Telecom HFP accounts",
                    exception
                )

                return null
            }
                ?: return null

        val accountId =
            account.id.orEmpty()

        val matchingDevice =
            bondedDevices
                .firstOrNull {
                    device ->

                    accountId.equals(
                        device.address,
                        ignoreCase = true
                    ) ||
                        accountId.contains(
                            device.address,
                            ignoreCase = true
                        )
                }

        /*
         * On the NXP target the HFP PhoneAccount identifies the actual
         * telephone endpoint. If the account ID cannot be matched but
         * there is only one paired device, use that Bluetooth name only
         * for display; the CONNECTED decision still comes from Telecom.
         */
        val displayDevice =
            matchingDevice
                ?: bondedDevices
                    .singleOrNull()

        val displayName =
            displayDevice
                ?.name
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "Connected phone"

        Log.i(
            TAG,
            "NXP Telecom HFP snapshot: " +
                "component=${account.componentName.flattenToShortString()} " +
                "accountId=$accountId " +
                "device=$displayName"
        )

        return TelecomHfpState(
            deviceName =
                displayName,

            accountId =
                accountId
        )
    }

    private fun PhoneAccountHandle
        .isBluetoothHfpClientAccount():
        Boolean {

        val component =
            componentName

        return component.packageName ==
            HFP_CLIENT_PACKAGE &&
            component.className ==
            HFP_CLIENT_CONNECTION_SERVICE
    }

    private fun publishSnapshot() {

        val snapshot =
            buildSnapshot()

        _state.value =
            snapshot

        Log.i(
            TAG,
            "Bluetooth state=" +
                snapshot.state +
                " phone=" +
                snapshot.connectedDeviceName.orEmpty()
        )
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toInfo():
        BluetoothDeviceInfo {

        val safeName =
            name
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "Unnamed device"

        return BluetoothDeviceInfo(
            name =
                safeName,

            addressSuffix =
                address.takeLast(
                    5
                )
        )
    }

    private data class PlatformHfpState(
        val connected: Boolean,
        val deviceName: String,
        val deviceAddress: String
    )

    private data class TelecomHfpState(
        val deviceName: String,
        val accountId: String
    )

    private companion object {

        const val TAG =
            "HN-Hfp"

        const val PLATFORM_PACKAGE =
            "com.hypernova.connectivity"

        const val PLATFORM_BIND_ACTION =
            "com.hypernova.connectivity.BIND"

        const val REQUIRED_PLATFORM_CONTRACT_VERSION =
            2

        const val HFP_CLIENT_PACKAGE =
            "com.android.bluetooth"

        const val HFP_CLIENT_CONNECTION_SERVICE =
            "com.android.bluetooth.hfpclient.HfpClientConnectionService"

        const val TEL_SCHEME =
            "tel"

        const val TELECOM_REFRESH_DELAY_SHORT_MS =
            750L

        const val TELECOM_REFRESH_DELAY_LONG_MS =
            2500L
    }
}
