/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.hypernova.connectivity;

import static com.hypernova.connectivity.HyperNovaWifiConstants.CONNECTION_AUTHENTICATING;
import static com.hypernova.connectivity.HyperNovaWifiConstants.CONNECTION_CONNECTED;
import static com.hypernova.connectivity.HyperNovaWifiConstants.CONNECTION_CONNECTING;
import static com.hypernova.connectivity.HyperNovaWifiConstants.CONNECTION_DISCONNECTED;
import static com.hypernova.connectivity.HyperNovaWifiConstants.CONNECTION_FAILED;
import static com.hypernova.connectivity.HyperNovaWifiConstants.CONNECTION_IDLE;
import static com.hypernova.connectivity.HyperNovaWifiConstants.CONNECTION_OBTAINING_IP;
import static com.hypernova.connectivity.HyperNovaWifiConstants.FAILURE_AUTHENTICATION;
import static com.hypernova.connectivity.HyperNovaWifiConstants.FAILURE_FRAMEWORK_REJECTED;
import static com.hypernova.connectivity.HyperNovaWifiConstants.FAILURE_INVALID_CONFIGURATION;
import static com.hypernova.connectivity.HyperNovaWifiConstants.FAILURE_NONE;
import static com.hypernova.connectivity.HyperNovaWifiConstants.FAILURE_PERMISSION_DENIED;
import static com.hypernova.connectivity.HyperNovaWifiConstants.FAILURE_TIMEOUT;
import static com.hypernova.connectivity.HyperNovaWifiConstants.FAILURE_WIFI_DISABLED;
import static com.hypernova.connectivity.HyperNovaWifiConstants.FAILURE_WRONG_PASSWORD;
import static com.hypernova.connectivity.HyperNovaWifiConstants.SECURITY_EAP;
import static com.hypernova.connectivity.HyperNovaWifiConstants.SECURITY_OPEN;
import static com.hypernova.connectivity.HyperNovaWifiConstants.SECURITY_UNKNOWN;
import static com.hypernova.connectivity.HyperNovaWifiConstants.SECURITY_WEP;
import static com.hypernova.connectivity.HyperNovaWifiConstants.SECURITY_WPA2_PSK;
import static com.hypernova.connectivity.HyperNovaWifiConstants.SECURITY_WPA2_WPA3_TRANSITION;
import static com.hypernova.connectivity.HyperNovaWifiConstants.SECURITY_WPA3_SAE;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent HyperNova Wi-Fi backend.
 *
 * This service owns:
 * - Wi-Fi scans
 * - saved network creation
 * - explicit network selection
 * - persistent Wi-Fi NetworkRequest
 * - connection timeout and failure reporting
 *
 * The HyperNova Settings UI must communicate with this service over AIDL.
 */
public final class HyperNovaConnectivityService
        extends Service {

    public static final String ACTION_START =
            "com.hypernova.connectivity.START";

    public static final String ACTION_BIND =
            "com.hypernova.connectivity.BIND";

    private static final String TAG =
            "HyperNovaConnectivity";

    private static final long WIFI_REQUEST_SETTLE_DELAY_MS =
            1_000L;

    private static final long WIFI_CONNECTION_TIMEOUT_MS =
            30_000L;

    private final Handler mHandler =
            new Handler(Looper.getMainLooper());

    private final RemoteCallbackList<
            IHyperNovaConnectivityCallback> mCallbacks =
            new RemoteCallbackList<>();

    private WifiManager mWifiManager;

    private ConnectivityManager mConnectivityManager;

    /*
     * HyperNova AAOS HFP platform integration.
     *
     * BluetoothHeadsetClient is a platform API and intentionally lives
     * inside this platform-signed privileged service instead of inside
     * the standalone HyperNova Phone application.
     */
    private BluetoothAdapter mBluetoothAdapter;

    private BluetoothHeadsetClient mHeadsetClient;

    private BluetoothDevice mConnectedHfpDevice;

    private ConnectivityManager.NetworkCallback
            mWifiNetworkCallback;

    private boolean mWifiRequestActive;

    private boolean mUserRequestedDisconnect;

    private Network mCurrentWifiNetwork;

    private PendingConnection mPendingConnection;

    private final Runnable mConnectionTimeoutRunnable =
            this::handleConnectionTimeout;

    private final BluetoothProfile.ServiceListener
            mBluetoothProfileListener =
            new BluetoothProfile.ServiceListener() {

                @Override
                public void onServiceConnected(
                        int profile,
                        BluetoothProfile proxy) {

                    if (profile
                            != BluetoothProfile.HEADSET_CLIENT
                            || !(proxy
                            instanceof BluetoothHeadsetClient)) {

                        return;
                    }

                    mHeadsetClient =
                            (BluetoothHeadsetClient) proxy;

                    Log.i(
                            TAG,
                            "Bluetooth HEADSET_CLIENT proxy connected");

                    mHandler.post(
                            HyperNovaConnectivityService
                                    .this::publishPhoneState);
                }

                @Override
                public void onServiceDisconnected(
                        int profile) {

                    if (profile
                            != BluetoothProfile.HEADSET_CLIENT) {

                        return;
                    }

                    mHeadsetClient = null;
                    mConnectedHfpDevice = null;

                    Log.w(
                            TAG,
                            "Bluetooth HEADSET_CLIENT proxy disconnected");

                    mHandler.post(
                            HyperNovaConnectivityService
                                    .this::publishPhoneState);
                }
            };

    private final BroadcastReceiver mBluetoothReceiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(
                        Context context,
                        Intent intent) {

                    if (intent == null) {
                        return;
                    }

                    String action =
                            intent.getAction();

                    if (BluetoothHeadsetClient
                            .ACTION_CONNECTION_STATE_CHANGED
                            .equals(action)
                            || BluetoothAdapter
                            .ACTION_STATE_CHANGED
                            .equals(action)) {

                        mHandler.post(
                                HyperNovaConnectivityService
                                        .this::publishPhoneState);
                    }
                }
            };

    private final BroadcastReceiver mWifiReceiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(
                        Context context,
                        Intent intent) {

                    if (intent == null) {
                        return;
                    }

                    String action =
                            intent.getAction();

                    if (WifiManager.WIFI_STATE_CHANGED_ACTION
                            .equals(action)) {

                        handleWifiStateChanged(
                                intent);

                    } else if (WifiManager
                            .SCAN_RESULTS_AVAILABLE_ACTION
                            .equals(action)) {

                        publishScanResults();

                    } else if (WifiManager
                            .NETWORK_STATE_CHANGED_ACTION
                            .equals(action)) {

                        handleNetworkStateChanged(
                                intent);

                    } else if (WifiManager
                            .SUPPLICANT_STATE_CHANGED_ACTION
                            .equals(action)) {

                        handleSupplicantStateChanged(
                                intent);

                    } else if (WifiManager
                            .RSSI_CHANGED_ACTION
                            .equals(action)) {

                        publishCurrentConnection();
                    }
                }
            };

    private final IHyperNovaConnectivityService.Stub mBinder =
            new IHyperNovaConnectivityService.Stub() {

                @Override
                public int getContractVersion() {

                    return HyperNovaWifiConstants
                            .CONTRACT_VERSION;
                }

                @Override
                public void registerCallback(
                        IHyperNovaConnectivityCallback callback) {

                    if (callback == null) {
                        return;
                    }

                    mCallbacks.register(
                            callback);

                    mHandler.post(
                            () -> sendSnapshot(callback));
                }

                @Override
                public void unregisterCallback(
                        IHyperNovaConnectivityCallback callback) {

                    if (callback == null) {
                        return;
                    }

                    mCallbacks.unregister(
                            callback);
                }

                @Override
                public boolean isWifiEnabled() {

                    return mWifiManager != null
                            && mWifiManager
                            .isWifiEnabled();
                }

                @Override
                public void setWifiEnabled(
                        boolean enabled) {

                    mHandler.post(
                            () -> setWifiEnabledInternal(
                                    enabled));
                }

                @Override
                public void requestWifiScan() {

                    mHandler.post(
                            HyperNovaConnectivityService
                                    .this::requestWifiScanInternal);
                }

                @Override
                public void connectWifi(
                        String ssid,
                        String password,
                        int securityType,
                        boolean hidden) {

                    final String safeSsid =
                            ssid == null
                                    ? ""
                                    : ssid.trim();

                    final String safePassword =
                            password == null
                                    ? ""
                                    : password;

                    mHandler.post(
                            () -> connectWifiInternal(
                                    safeSsid,
                                    safePassword,
                                    securityType,
                                    hidden));
                }

                @Override
                public void disconnectWifi() {

                    mHandler.post(
                            HyperNovaConnectivityService
                                    .this::disconnectWifiInternal);
                }

                @Override
                public void forgetWifi(
                        String ssid) {

                    final String safeSsid =
                            ssid == null
                                    ? ""
                                    : ssid.trim();

                    mHandler.post(
                            () -> forgetWifiInternal(
                                    safeSsid));
                }

                @Override
                public boolean isHfpConnected() {

                    return getConnectedHfpDevice() != null;
                }

                @Override
                public String getHfpDeviceName() {

                    BluetoothDevice device =
                            getConnectedHfpDevice();

                    return getSafeDeviceName(
                            device);
                }

                @Override
                public String getHfpDeviceAddress() {

                    BluetoothDevice device =
                            getConnectedHfpDevice();

                    return getSafeDeviceAddress(
                            device);
                }

                @Override
                public void refreshState() {

                    mHandler.post(
                            HyperNovaConnectivityService
                                    .this::publishCompleteState);
                }
            };

    @Override
    public void onCreate() {

        super.onCreate();

        mWifiManager =
                getSystemService(
                        WifiManager.class);

        mConnectivityManager =
                getSystemService(
                        ConnectivityManager.class);

        BluetoothManager bluetoothManager =
                getSystemService(
                        BluetoothManager.class);

        mBluetoothAdapter =
                bluetoothManager == null
                        ? null
                        : bluetoothManager.getAdapter();

        if (mBluetoothAdapter != null) {

            try {

                mBluetoothAdapter.getProfileProxy(
                        this,
                        mBluetoothProfileListener,
                        BluetoothProfile.HEADSET_CLIENT);

            } catch (SecurityException exception) {

                Log.e(
                        TAG,
                        "Unable to obtain HEADSET_CLIENT proxy",
                        exception);
            }
        }

        IntentFilter bluetoothFilter =
                new IntentFilter();

        bluetoothFilter.addAction(
                BluetoothHeadsetClient
                        .ACTION_CONNECTION_STATE_CHANGED);

        bluetoothFilter.addAction(
                BluetoothAdapter.ACTION_STATE_CHANGED);

        registerReceiver(
                mBluetoothReceiver,
                bluetoothFilter,
                Context.RECEIVER_EXPORTED);

        IntentFilter filter =
                new IntentFilter();

        filter.addAction(
                WifiManager.WIFI_STATE_CHANGED_ACTION);

        filter.addAction(
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);

        filter.addAction(
                WifiManager.NETWORK_STATE_CHANGED_ACTION);

        filter.addAction(
                WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);

        filter.addAction(
                WifiManager.RSSI_CHANGED_ACTION);

        registerReceiver(
                mWifiReceiver,
                filter,
                Context.RECEIVER_EXPORTED);

        Log.i(
                TAG,
                "HyperNova connectivity backend created");

        if (mWifiManager != null
                && mWifiManager.isWifiEnabled()) {

            ensurePersistentWifiRequest();
        }

        publishCompleteState();
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId) {

        Log.i(
                TAG,
                "Connectivity backend started");

        if (mWifiManager != null
                && mWifiManager.isWifiEnabled()
                && !mUserRequestedDisconnect) {

            ensurePersistentWifiRequest();
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(
            Intent intent) {

        Log.i(
                TAG,
                "Client bound to connectivity backend");

        return mBinder;
    }

    @Override
    public void onDestroy() {

        mHandler.removeCallbacks(
                mConnectionTimeoutRunnable);

        releasePersistentWifiRequest();

        try {

            unregisterReceiver(
                    mBluetoothReceiver);

        } catch (RuntimeException exception) {

            Log.w(
                    TAG,
                    "Bluetooth receiver was already removed",
                    exception);
        }

        if (mBluetoothAdapter != null
                && mHeadsetClient != null) {

            try {

                mBluetoothAdapter.closeProfileProxy(
                        BluetoothProfile.HEADSET_CLIENT,
                        mHeadsetClient);

            } catch (RuntimeException exception) {

                Log.w(
                        TAG,
                        "Unable to close HEADSET_CLIENT proxy",
                        exception);
            }
        }

        mHeadsetClient = null;
        mConnectedHfpDevice = null;

        try {

            unregisterReceiver(
                    mWifiReceiver);

        } catch (RuntimeException exception) {

            Log.w(
                    TAG,
                    "Wi-Fi receiver was already removed",
                    exception);
        }

        mCallbacks.kill();

        Log.w(
                TAG,
                "HyperNova connectivity backend destroyed");

        super.onDestroy();
    }

    private void setWifiEnabledInternal(
            boolean enabled) {

        if (mWifiManager == null) {

            notifyConnectionState(
                    "",
                    CONNECTION_FAILED,
                    FAILURE_FRAMEWORK_REJECTED);

            return;
        }

        try {

            if (!enabled) {

                mUserRequestedDisconnect = true;

                cancelPendingConnection();

                releasePersistentWifiRequest();
            } else {

                mUserRequestedDisconnect = false;
            }

            boolean accepted =
                    mWifiManager.setWifiEnabled(
                            enabled);

            Log.i(
                    TAG,
                    "setWifiEnabled("
                            + enabled
                            + ") accepted="
                            + accepted);

            if (!accepted) {

                notifyConnectionState(
                        "",
                        CONNECTION_FAILED,
                        FAILURE_FRAMEWORK_REJECTED);
            }

        } catch (SecurityException exception) {

            Log.e(
                    TAG,
                    "Permission denied while changing Wi-Fi",
                    exception);

            notifyConnectionState(
                    "",
                    CONNECTION_FAILED,
                    FAILURE_PERMISSION_DENIED);

        } catch (RuntimeException exception) {

            Log.e(
                    TAG,
                    "Unable to change Wi-Fi state",
                    exception);

            notifyConnectionState(
                    "",
                    CONNECTION_FAILED,
                    FAILURE_FRAMEWORK_REJECTED);
        }
    }

    private void requestWifiScanInternal() {

        if (mWifiManager == null
                || !mWifiManager.isWifiEnabled()) {

            notifyScanState(
                    false);

            return;
        }

        notifyScanState(
                true);

        try {

            boolean accepted =
                    mWifiManager.startScan();

            Log.i(
                    TAG,
                    "startScan accepted="
                            + accepted);

            if (!accepted) {

                notifyScanState(
                        false);

                publishScanResults();
            }

        } catch (RuntimeException exception) {

            Log.e(
                    TAG,
                    "Unable to start Wi-Fi scan",
                    exception);

            notifyScanState(
                    false);
        }
    }

    private void connectWifiInternal(
            String ssid,
            String password,
            int securityType,
            boolean hidden) {

        if (TextUtils.isEmpty(ssid)) {

            notifyConnectionState(
                    ssid,
                    CONNECTION_FAILED,
                    FAILURE_INVALID_CONFIGURATION);

            return;
        }

        if (securityType != SECURITY_OPEN
                && TextUtils.isEmpty(password)) {

            notifyConnectionState(
                    ssid,
                    CONNECTION_FAILED,
                    FAILURE_INVALID_CONFIGURATION);

            return;
        }

        if (securityType == SECURITY_EAP
                || securityType == SECURITY_UNKNOWN) {

            notifyConnectionState(
                    ssid,
                    CONNECTION_FAILED,
                    FAILURE_INVALID_CONFIGURATION);

            return;
        }

        mUserRequestedDisconnect = false;

        cancelPendingConnection();

        mPendingConnection =
                new PendingConnection(
                        ssid,
                        password,
                        securityType,
                        hidden);

        notifyConnectionState(
                ssid,
                CONNECTION_CONNECTING,
                FAILURE_NONE);

        mHandler.postDelayed(
                mConnectionTimeoutRunnable,
                WIFI_CONNECTION_TIMEOUT_MS);

        if (mWifiManager == null) {

            failPendingConnection(
                    FAILURE_FRAMEWORK_REJECTED);

            return;
        }

        if (!mWifiManager.isWifiEnabled()) {

            boolean accepted;

            try {

                accepted =
                        mWifiManager.setWifiEnabled(
                                true);

            } catch (RuntimeException exception) {

                Log.e(
                        TAG,
                        "Unable to enable Wi-Fi before connect",
                        exception);

                failPendingConnection(
                        FAILURE_WIFI_DISABLED);

                return;
            }

            if (!accepted) {

                failPendingConnection(
                        FAILURE_WIFI_DISABLED);
            }

            return;
        }

        beginPendingConnection();
    }

    private void beginPendingConnection() {

        PendingConnection pending =
                mPendingConnection;

        if (pending == null) {
            return;
        }

        if (!ensurePersistentWifiRequest()) {

            failPendingConnection(
                    FAILURE_FRAMEWORK_REJECTED);

            return;
        }

        mHandler.postDelayed(
                () -> {

                    if (mPendingConnection != pending) {
                        return;
                    }

                    addAndEnableNetwork(
                            pending);
                },
                WIFI_REQUEST_SETTLE_DELAY_MS);
    }

    private void addAndEnableNetwork(
            PendingConnection pending) {

        WifiConfiguration configuration;

        try {

            configuration =
                    buildWifiConfiguration(
                            pending);

        } catch (IllegalArgumentException exception) {

            Log.e(
                    TAG,
                    "Invalid Wi-Fi configuration",
                    exception);

            failPendingConnection(
                    FAILURE_INVALID_CONFIGURATION);

            return;
        }

        WifiManager.AddNetworkResult result;

        try {

            result =
                    mWifiManager.addNetworkPrivileged(
                            configuration);

        } catch (SecurityException exception) {

            Log.e(
                    TAG,
                    "Permission denied adding Wi-Fi network",
                    exception);

            failPendingConnection(
                    FAILURE_PERMISSION_DENIED);

            return;

        } catch (RuntimeException exception) {

            Log.e(
                    TAG,
                    "Unable to add Wi-Fi network",
                    exception);

            failPendingConnection(
                    FAILURE_FRAMEWORK_REJECTED);

            return;
        }

        Log.i(
                TAG,
                "addNetworkPrivileged ssid="
                        + pending.ssid
                        + " status="
                        + result.statusCode
                        + " networkId="
                        + result.networkId);

        if (result.statusCode
                != WifiManager.AddNetworkResult
                .STATUS_SUCCESS
                || result.networkId < 0) {

            int reason =
                    result.statusCode
                                    == WifiManager.AddNetworkResult
                                    .STATUS_NO_PERMISSION
                            || result.statusCode
                                    == WifiManager.AddNetworkResult
                                    .STATUS_NO_PERMISSION_MODIFY_CONFIG
                            ? FAILURE_PERMISSION_DENIED
                            : FAILURE_INVALID_CONFIGURATION;

            failPendingConnection(
                    reason);

            return;
        }

        boolean accepted;

        try {

            accepted =
                    mWifiManager.enableNetwork(
                            result.networkId,
                            true);

        } catch (SecurityException exception) {

            Log.e(
                    TAG,
                    "Permission denied enabling Wi-Fi network",
                    exception);

            failPendingConnection(
                    FAILURE_PERMISSION_DENIED);

            return;

        } catch (RuntimeException exception) {

            Log.e(
                    TAG,
                    "Unable to enable Wi-Fi network",
                    exception);

            failPendingConnection(
                    FAILURE_FRAMEWORK_REJECTED);

            return;
        }

        Log.i(
                TAG,
                "enableNetwork networkId="
                        + result.networkId
                        + " accepted="
                        + accepted);

        if (!accepted) {

            failPendingConnection(
                    FAILURE_FRAMEWORK_REJECTED);
        }
    }

    private WifiConfiguration buildWifiConfiguration(
            PendingConnection pending) {

        WifiConfiguration configuration =
                new WifiConfiguration();

        configuration.SSID =
                quote(
                        pending.ssid);

        configuration.hiddenSSID =
                pending.hidden;

        switch (pending.securityType) {

            case SECURITY_OPEN:

                configuration.setSecurityParams(
                        WifiConfiguration
                                .SECURITY_TYPE_OPEN);

                break;

            case SECURITY_WEP:

                configuration.setSecurityParams(
                        WifiConfiguration
                                .SECURITY_TYPE_WEP);

                configuration.wepKeys[0] =
                        quote(
                                pending.password);

                configuration.wepTxKeyIndex =
                        0;

                break;

            case SECURITY_WPA2_PSK:
            case SECURITY_WPA2_WPA3_TRANSITION:

                configuration.setSecurityParams(
                        WifiConfiguration
                                .SECURITY_TYPE_PSK);

                configuration.preSharedKey =
                        quote(
                                pending.password);

                break;

            case SECURITY_WPA3_SAE:

                configuration.setSecurityParams(
                        WifiConfiguration
                                .SECURITY_TYPE_SAE);

                configuration.preSharedKey =
                        quote(
                                pending.password);

                break;

            default:

                throw new IllegalArgumentException(
                        "Unsupported security type: "
                                + pending.securityType);
        }

        return configuration;
    }

    private void disconnectWifiInternal() {

        mUserRequestedDisconnect =
                true;

        cancelPendingConnection();

        releasePersistentWifiRequest();

        try {

            boolean accepted =
                    mWifiManager != null
                            && mWifiManager.disconnect();

            Log.i(
                    TAG,
                    "disconnect accepted="
                            + accepted);

            notifyConnectionState(
                    getCurrentSsid(),
                    CONNECTION_DISCONNECTED,
                    FAILURE_NONE);

        } catch (RuntimeException exception) {

            Log.e(
                    TAG,
                    "Unable to disconnect Wi-Fi",
                    exception);

            notifyConnectionState(
                    getCurrentSsid(),
                    CONNECTION_FAILED,
                    FAILURE_FRAMEWORK_REJECTED);
        }
    }

    private void forgetWifiInternal(
            String ssid) {

        if (mWifiManager == null
                || TextUtils.isEmpty(ssid)) {

            return;
        }

        List<WifiConfiguration> configurations;

        try {

            configurations =
                    mWifiManager.getConfiguredNetworks();

        } catch (RuntimeException exception) {

            Log.e(
                    TAG,
                    "Unable to read configured networks",
                    exception);

            return;
        }

        if (configurations == null) {
            return;
        }

        boolean removed =
                false;

        for (WifiConfiguration configuration
                : configurations) {

            if (!ssid.equals(
                    unquote(configuration.SSID))) {

                continue;
            }

            try {

                removed |=
                        mWifiManager.removeNetwork(
                                configuration.networkId);

            } catch (RuntimeException exception) {

                Log.e(
                        TAG,
                        "Unable to remove network "
                                + ssid,
                        exception);
            }
        }

        Log.i(
                TAG,
                "forgetWifi ssid="
                        + ssid
                        + " removed="
                        + removed);

        publishCompleteState();
    }

    private boolean ensurePersistentWifiRequest() {

        if (mWifiRequestActive) {
            return true;
        }

        if (mConnectivityManager == null) {

            Log.e(
                    TAG,
                    "ConnectivityManager unavailable");

            return false;
        }

        NetworkRequest request =
                new NetworkRequest.Builder()
                        .addTransportType(
                                NetworkCapabilities
                                        .TRANSPORT_WIFI)
                        .addCapability(
                                NetworkCapabilities
                                        .NET_CAPABILITY_INTERNET)
                        .build();

        ConnectivityManager.NetworkCallback callback =
                new ConnectivityManager.NetworkCallback() {

                    @Override
                    public void onAvailable(
                            Network network) {

                        mHandler.post(
                                () -> {

                                    mCurrentWifiNetwork =
                                            network;

                                    Log.i(
                                            TAG,
                                            "Wi-Fi network available: "
                                                    + network);

                                    publishCurrentConnection();
                                });
                    }

                    @Override
                    public void onCapabilitiesChanged(
                            Network network,
                            NetworkCapabilities capabilities) {

                        mHandler.post(
                                () -> {

                                    mCurrentWifiNetwork =
                                            network;

                                    publishCurrentConnection();
                                });
                    }

                    @Override
                    public void onLinkPropertiesChanged(
                            Network network,
                            LinkProperties linkProperties) {

                        mHandler.post(
                                () -> {

                                    mCurrentWifiNetwork =
                                            network;

                                    publishCurrentConnection();
                                });
                    }

                    @Override
                    public void onLost(
                            Network network) {

                        mHandler.post(
                                () -> {

                                    if (network.equals(
                                            mCurrentWifiNetwork)) {

                                        mCurrentWifiNetwork =
                                                null;
                                    }

                                    Log.w(
                                            TAG,
                                            "Wi-Fi network lost: "
                                                    + network);

                                    notifyConnectionState(
                                            getCurrentSsid(),
                                            CONNECTION_DISCONNECTED,
                                            FAILURE_NONE);
                                });
                    }
                };

        try {

            mConnectivityManager.requestNetwork(
                    request,
                    callback);

            mWifiNetworkCallback =
                    callback;

            mWifiRequestActive =
                    true;

            Log.i(
                    TAG,
                    "Persistent Wi-Fi NetworkRequest registered");

            return true;

        } catch (SecurityException exception) {

            Log.e(
                    TAG,
                    "Permission denied registering NetworkRequest",
                    exception);

            return false;

        } catch (RuntimeException exception) {

            Log.e(
                    TAG,
                    "Unable to register Wi-Fi NetworkRequest",
                    exception);

            return false;
        }
    }

    private void releasePersistentWifiRequest() {

        if (!mWifiRequestActive
                || mConnectivityManager == null
                || mWifiNetworkCallback == null) {

            mWifiRequestActive =
                    false;

            mWifiNetworkCallback =
                    null;

            return;
        }

        try {

            mConnectivityManager
                    .unregisterNetworkCallback(
                            mWifiNetworkCallback);

        } catch (RuntimeException exception) {

            Log.w(
                    TAG,
                    "NetworkRequest was already removed",
                    exception);
        }

        mWifiRequestActive =
                false;

        mWifiNetworkCallback =
                null;

        mCurrentWifiNetwork =
                null;

        Log.i(
                TAG,
                "Persistent Wi-Fi NetworkRequest released");
    }

    private void handleWifiStateChanged(
            Intent intent) {

        int state =
                intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN);

        boolean enabled =
                state
                        == WifiManager.WIFI_STATE_ENABLED;

        notifyWifiState(
                enabled);

        if (enabled) {

            if (!mUserRequestedDisconnect) {

                ensurePersistentWifiRequest();
            }

            if (mPendingConnection != null) {

                beginPendingConnection();
            }

        } else if (state
                == WifiManager.WIFI_STATE_DISABLED) {

            releasePersistentWifiRequest();

            if (mPendingConnection != null) {

                failPendingConnection(
                        FAILURE_WIFI_DISABLED);
            }
        }
    }

    private void handleNetworkStateChanged(
            Intent intent) {

        NetworkInfo networkInfo =
                intent.getParcelableExtra(
                        WifiManager.EXTRA_NETWORK_INFO,
                        NetworkInfo.class);

        if (networkInfo == null) {
            return;
        }

        NetworkInfo.DetailedState detailedState =
                networkInfo.getDetailedState();

        String ssid =
                mPendingConnection != null
                        ? mPendingConnection.ssid
                        : getCurrentSsid();

        if (detailedState
                == NetworkInfo.DetailedState.CONNECTING) {

            notifyConnectionState(
                    ssid,
                    CONNECTION_CONNECTING,
                    FAILURE_NONE);

        } else if (detailedState
                == NetworkInfo.DetailedState.AUTHENTICATING) {

            notifyConnectionState(
                    ssid,
                    CONNECTION_AUTHENTICATING,
                    FAILURE_NONE);

        } else if (detailedState
                == NetworkInfo.DetailedState.OBTAINING_IPADDR) {

            notifyConnectionState(
                    ssid,
                    CONNECTION_OBTAINING_IP,
                    FAILURE_NONE);

        } else if (detailedState
                == NetworkInfo.DetailedState.CONNECTED) {

            publishCurrentConnection();

        } else if (detailedState
                == NetworkInfo.DetailedState.DISCONNECTED) {

            if (mPendingConnection == null) {

                notifyConnectionState(
                        ssid,
                        CONNECTION_DISCONNECTED,
                        FAILURE_NONE);
            }
        }
    }

    private void handleSupplicantStateChanged(
            Intent intent) {

        int error =
                intent.getIntExtra(
                        WifiManager.EXTRA_SUPPLICANT_ERROR,
                        -1);

        if (error
                == WifiManager.ERROR_AUTHENTICATING
                && mPendingConnection != null) {

            Log.e(
                    TAG,
                    "Supplicant authentication failure for "
                            + mPendingConnection.ssid);

            failPendingConnection(
                    FAILURE_WRONG_PASSWORD);
        }
    }

    private void handleConnectionTimeout() {

        if (mPendingConnection == null) {
            return;
        }

        Log.e(
                TAG,
                "Connection timeout for "
                        + mPendingConnection.ssid);

        failPendingConnection(
                FAILURE_TIMEOUT);
    }

    private void failPendingConnection(
            int failureReason) {

        PendingConnection pending =
                mPendingConnection;

        cancelPendingConnection();

        notifyConnectionState(
                pending == null
                        ? ""
                        : pending.ssid,
                CONNECTION_FAILED,
                failureReason);
    }

    private void cancelPendingConnection() {

        mHandler.removeCallbacks(
                mConnectionTimeoutRunnable);

        mPendingConnection =
                null;
    }

    private void publishCompleteState() {

        notifyWifiState(
                mWifiManager != null
                        && mWifiManager.isWifiEnabled());

        publishCurrentConnection();

        publishScanResults();
    }

    private void publishScanResults() {

        notifyScanState(
                false);

        if (mWifiManager == null) {
            return;
        }

        List<ScanResult> scanResults;

        try {

            scanResults =
                    mWifiManager.getScanResults();

        } catch (RuntimeException exception) {

            Log.e(
                    TAG,
                    "Unable to obtain scan results",
                    exception);

            scanResults =
                    Collections.emptyList();
        }

        Set<String> savedSsids =
                getSavedSsids();

        String currentSsid =
                getCurrentSsid();

        broadcastCallback(
                IHyperNovaConnectivityCallback
                        ::onWifiNetworksCleared);

        for (ScanResult result
                : scanResults) {

            String ssid =
                    result.SSID;

            if (TextUtils.isEmpty(ssid)) {
                continue;
            }

            int securityType =
                    detectSecurityType(
                            result.capabilities);

            int signalLevel =
                    mWifiManager.calculateSignalLevel(
                            result.level);

            boolean saved =
                    savedSsids.contains(
                            ssid);

            boolean connected =
                    ssid.equals(
                            currentSsid);

            broadcastCallback(
                    callback ->
                            callback.onWifiNetworkFound(
                                    ssid,
                                    result.BSSID == null
                                            ? ""
                                            : result.BSSID,
                                    signalLevel,
                                    securityType,
                                    saved,
                                    connected));
        }

        broadcastCallback(
                IHyperNovaConnectivityCallback
                        ::onWifiScanCompleted);
    }

    private void publishCurrentConnection() {

        WifiInfo wifiInfo =
                getCurrentWifiInfo();

        if (wifiInfo == null
                || wifiInfo.getNetworkId() < 0) {

            return;
        }

        String ssid =
                unquote(
                        wifiInfo.getSSID());

        if (TextUtils.isEmpty(ssid)
                || WifiManager.UNKNOWN_SSID.equals(
                        ssid)) {

            return;
        }

        if (mPendingConnection != null
                && ssid.equals(
                        mPendingConnection.ssid)) {

            cancelPendingConnection();
        }

        String bssid =
                wifiInfo.getBSSID() == null
                        ? ""
                        : wifiInfo.getBSSID();

        int signalLevel =
                mWifiManager.calculateSignalLevel(
                        wifiInfo.getRssi());

        String ipAddress =
                findIpv4Address();

        notifyConnectionState(
                ssid,
                CONNECTION_CONNECTED,
                FAILURE_NONE);

        broadcastCallback(
                callback ->
                        callback.onConnectedWifiChanged(
                                ssid,
                                bssid,
                                ipAddress,
                                signalLevel));
    }

    private WifiInfo getCurrentWifiInfo() {

        if (mWifiManager == null) {
            return null;
        }

        try {

            return mWifiManager
                    .getConnectionInfo();

        } catch (RuntimeException exception) {

            Log.e(
                    TAG,
                    "Unable to read current Wi-Fi info",
                    exception);

            return null;
        }
    }

    private String findIpv4Address() {

        Network network =
                mCurrentWifiNetwork;

        if (network == null
                && mConnectivityManager != null) {

            for (Network candidate
                    : mConnectivityManager.getAllNetworks()) {

                NetworkCapabilities capabilities =
                        mConnectivityManager
                                .getNetworkCapabilities(
                                        candidate);

                if (capabilities != null
                        && capabilities.hasTransport(
                                NetworkCapabilities
                                        .TRANSPORT_WIFI)) {

                    network =
                            candidate;

                    break;
                }
            }
        }

        if (network == null
                || mConnectivityManager == null) {

            return "";
        }

        LinkProperties properties =
                mConnectivityManager
                        .getLinkProperties(
                                network);

        if (properties == null) {
            return "";
        }

        for (LinkAddress linkAddress
                : properties.getLinkAddresses()) {

            InetAddress address =
                    linkAddress.getAddress();

            if (address
                    instanceof Inet4Address) {

                return address
                        .getHostAddress();
            }
        }

        return "";
    }

    private Set<String> getSavedSsids() {

        if (mWifiManager == null) {

            return Collections.emptySet();
        }

        List<WifiConfiguration> configurations;

        try {

            configurations =
                    mWifiManager.getConfiguredNetworks();

        } catch (RuntimeException exception) {

            return Collections.emptySet();
        }

        if (configurations == null) {

            return Collections.emptySet();
        }

        Set<String> savedSsids =
                new HashSet<>();

        for (WifiConfiguration configuration
                : configurations) {

            String ssid =
                    unquote(
                            configuration.SSID);

            if (!TextUtils.isEmpty(ssid)) {

                savedSsids.add(
                        ssid);
            }
        }

        return savedSsids;
    }

    private String getCurrentSsid() {

        WifiInfo wifiInfo =
                getCurrentWifiInfo();

        if (wifiInfo == null) {
            return "";
        }

        String ssid =
                unquote(
                        wifiInfo.getSSID());

        if (WifiManager.UNKNOWN_SSID.equals(
                ssid)) {

            return "";
        }

        return ssid;
    }

    private int detectSecurityType(
            String capabilities) {

        String value =
                capabilities == null
                        ? ""
                        : capabilities.toUpperCase();

        boolean hasPsk =
                value.contains(
                        "PSK");

        boolean hasSae =
                value.contains(
                        "SAE");

        if (hasPsk && hasSae) {

            return SECURITY_WPA2_WPA3_TRANSITION;
        }

        if (hasSae) {

            return SECURITY_WPA3_SAE;
        }

        if (hasPsk) {

            return SECURITY_WPA2_PSK;
        }

        if (value.contains(
                "EAP")) {

            return SECURITY_EAP;
        }

        if (value.contains(
                "WEP")) {

            return SECURITY_WEP;
        }

        if (value.contains(
                "OWE")) {

            return SECURITY_UNKNOWN;
        }

        return SECURITY_OPEN;
    }

    private BluetoothDevice getConnectedHfpDevice() {

        BluetoothHeadsetClient headsetClient =
                mHeadsetClient;

        if (headsetClient == null) {

            mConnectedHfpDevice = null;
            return null;
        }

        try {

            List<BluetoothDevice> devices =
                    headsetClient.getConnectedDevices();

            if (devices == null
                    || devices.isEmpty()) {

                mConnectedHfpDevice = null;
                return null;
            }

            BluetoothDevice device =
                    devices.get(0);

            mConnectedHfpDevice =
                    device;

            return device;

        } catch (SecurityException exception) {

            Log.e(
                    TAG,
                    "BLUETOOTH_CONNECT denied while reading HFP state",
                    exception);

            mConnectedHfpDevice = null;
            return null;

        } catch (RuntimeException exception) {

            Log.e(
                    TAG,
                    "Unable to read HFP Client state",
                    exception);

            mConnectedHfpDevice = null;
            return null;
        }
    }

    private void publishPhoneState() {

        BluetoothDevice device =
                getConnectedHfpDevice();

        boolean connected =
                device != null;

        String name =
                getSafeDeviceName(
                        device);

        String address =
                getSafeDeviceAddress(
                        device);

        Log.i(
                TAG,
                "HFP phone state connected="
                        + connected
                        + " name="
                        + name
                        + " address="
                        + address);

        broadcastCallback(
                callback ->
                        callback.onPhoneConnectionChanged(
                                connected,
                                name,
                                address));
    }

    private String getSafeDeviceName(
            BluetoothDevice device) {

        if (device == null) {
            return "";
        }

        try {

            String name =
                    device.getName();

            return name == null
                    ? ""
                    : name;

        } catch (SecurityException exception) {

            Log.w(
                    TAG,
                    "Unable to read Bluetooth device name",
                    exception);

            return "";
        }
    }

    private String getSafeDeviceAddress(
            BluetoothDevice device) {

        if (device == null) {
            return "";
        }

        try {

            String address =
                    device.getAddress();

            return address == null
                    ? ""
                    : address;

        } catch (SecurityException exception) {

            Log.w(
                    TAG,
                    "Unable to read Bluetooth device address",
                    exception);

            return "";
        }
    }

    private void sendSnapshot(
            IHyperNovaConnectivityCallback callback) {

        if (callback == null) {
            return;
        }

        try {

            callback.onWifiStateChanged(
                    mWifiManager != null
                            && mWifiManager.isWifiEnabled());

            BluetoothDevice device =
                    getConnectedHfpDevice();

            callback.onPhoneConnectionChanged(
                    device != null,
                    getSafeDeviceName(
                            device),
                    getSafeDeviceAddress(
                            device));

        } catch (RemoteException exception) {

            Log.w(
                    TAG,
                    "Unable to send initial state",
                    exception);
        }
    }

    private void notifyWifiState(
            boolean enabled) {

        broadcastCallback(
                callback ->
                        callback.onWifiStateChanged(
                                enabled));
    }

    private void notifyScanState(
            boolean scanning) {

        broadcastCallback(
                callback ->
                        callback.onWifiScanStateChanged(
                                scanning));
    }

    private void notifyConnectionState(
            String ssid,
            int state,
            int failureReason) {

        Log.i(
                TAG,
                "connectionState ssid="
                        + ssid
                        + " state="
                        + state
                        + " failure="
                        + failureReason);

        broadcastCallback(
                callback ->
                        callback.onWifiConnectionStateChanged(
                                ssid == null
                                        ? ""
                                        : ssid,
                                state,
                                failureReason));
    }

    private void broadcastCallback(
            CallbackAction action) {

        int count =
                mCallbacks.beginBroadcast();

        try {

            for (int index = 0;
                    index < count;
                    index++) {

                try {

                    action.run(
                            mCallbacks
                                    .getBroadcastItem(
                                            index));

                } catch (RemoteException exception) {

                    Log.w(
                            TAG,
                            "Connectivity callback failed",
                            exception);
                }
            }

        } finally {

            mCallbacks.finishBroadcast();
        }
    }

    private static String quote(
            String value) {

        String escaped =
                value.replace(
                        "\\",
                        "\\\\")
                        .replace(
                                "\"",
                                "\\\"");

        return "\""
                + escaped
                + "\"";
    }

    private static String unquote(
            String value) {

        if (value == null) {
            return "";
        }

        if (value.length() >= 2
                && value.startsWith("\"")
                && value.endsWith("\"")) {

            return value.substring(
                    1,
                    value.length() - 1);
        }

        return value;
    }

    private interface CallbackAction {

        void run(
                IHyperNovaConnectivityCallback callback)
                throws RemoteException;
    }

    private static final class PendingConnection {

        final String ssid;

        final String password;

        final int securityType;

        final boolean hidden;

        PendingConnection(
                String ssid,
                String password,
                int securityType,
                boolean hidden) {

            this.ssid =
                    ssid;

            this.password =
                    password;

            this.securityType =
                    securityType;

            this.hidden =
                    hidden;
        }
    }
}
