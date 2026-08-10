/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.android.car.settings.hypernova;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.wifitrackerlib.WifiEntry;
import com.hypernova.connectivity.HyperNovaWifiConstants;
import com.hypernova.connectivity.IHyperNovaConnectivityCallback;
import com.hypernova.connectivity.IHyperNovaConnectivityService;

import java.util.List;

/**
 * Single client-side gateway between HyperNova Settings UI and the
 * privileged HyperNovaConnectivityService.
 *
 * <p>The Settings UI should not directly own Wi-Fi association.
 * The persistent privileged service owns:
 *
 * <ul>
 *     <li>Wi-Fi power control</li>
 *     <li>Scanning</li>
 *     <li>Network configuration</li>
 *     <li>Association</li>
 *     <li>Disconnect</li>
 *     <li>Forget</li>
 *     <li>Connection state and failure reporting</li>
 * </ul>
 *
 * <p>CarWifiManager / WifiEntry may still be used by Settings as a
 * display/tracker model.
 */
final class HyperNovaConnectivityClient {

    private static final String TAG =
            "HyperNovaConnClient";

    private static final String SERVICE_ACTION =
            "com.hypernova.connectivity.BIND";

    private static final String SERVICE_PACKAGE =
            "com.hypernova.connectivity";

    private final Context mContext;

    private final Handler mMainHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable mOnChanged;

    /*
     * This WifiManager is not the primary connection engine.
     *
     * It is retained only for reading an existing saved WifiConfiguration
     * when a WifiEntry represents a previously saved network.
     */
    private final WifiManager mWifiManager;

    @Nullable
    private volatile IHyperNovaConnectivityService mService;

    private boolean mBindRequested;

    private volatile boolean mWifiEnabled;

    private volatile boolean mScanning;

    @Nullable
    private volatile String mConnectedSsid;

    @Nullable
    private volatile String mConnectedBssid;

    @Nullable
    private volatile String mConnectedIpAddress;

    private volatile int mConnectedSignalLevel;

    @Nullable
    private volatile String mConnectionSsid;

    private volatile int mConnectionState =
            HyperNovaWifiConstants.CONNECTION_IDLE;

    private volatile int mFailureReason =
            HyperNovaWifiConstants.FAILURE_NONE;

    HyperNovaConnectivityClient(
            Context context,
            Runnable onChanged) {

        mContext =
                context.getApplicationContext();

        mOnChanged =
                onChanged;

        mWifiManager =
                mContext.getSystemService(
                        WifiManager.class);
    }

    private final ServiceConnection mConnection =
            new ServiceConnection() {

                @Override
                public void onServiceConnected(
                        ComponentName name,
                        IBinder binder) {

                    mService =
                            IHyperNovaConnectivityService
                                    .Stub
                                    .asInterface(binder);

                    Log.i(
                            TAG,
                            "AIDL_CONNECTED component="
                                    + name.flattenToShortString());

                    try {

                        int version =
                                mService.getContractVersion();

                        Log.i(
                                TAG,
                                "CONTRACT_VERSION="
                                        + version
                                        + " expected="
                                        + HyperNovaWifiConstants
                                                .CONTRACT_VERSION);

                        if (version
                                != HyperNovaWifiConstants
                                        .CONTRACT_VERSION) {

                            Log.e(
                                    TAG,
                                    "AIDL contract mismatch");
                        }

                        mService.registerCallback(
                                mCallback);

                        mService.refreshState();

                    } catch (RemoteException exception) {

                        Log.e(
                                TAG,
                                "Unable to initialize connectivity service",
                                exception);
                    }

                    postChanged();
                }

                @Override
                public void onServiceDisconnected(
                        ComponentName name) {

                    Log.w(
                            TAG,
                            "AIDL_DISCONNECTED component="
                                    + name.flattenToShortString());

                    mService =
                            null;

                    postChanged();
                }

                @Override
                public void onBindingDied(
                        ComponentName name) {

                    Log.e(
                            TAG,
                            "AIDL_BINDING_DIED component="
                                    + name.flattenToShortString());

                    mService =
                            null;

                    mBindRequested =
                            false;

                    postChanged();
                }

                @Override
                public void onNullBinding(
                        ComponentName name) {

                    Log.e(
                            TAG,
                            "AIDL_NULL_BINDING component="
                                    + name.flattenToShortString());

                    mService =
                            null;

                    postChanged();
                }
            };

    private final IHyperNovaConnectivityCallback mCallback =
            new IHyperNovaConnectivityCallback.Stub() {

                @Override
                public void onWifiStateChanged(
                        boolean enabled) {

                    mWifiEnabled =
                            enabled;

                    Log.i(
                            TAG,
                            "CALLBACK_WIFI_STATE enabled="
                                    + enabled);

                    postChanged();
                }

                @Override
                public void onWifiScanStateChanged(
                        boolean scanning) {

                    mScanning =
                            scanning;

                    Log.i(
                            TAG,
                            "CALLBACK_SCAN_STATE scanning="
                                    + scanning);

                    postChanged();
                }

                @Override
                public void onWifiNetworksCleared() {

                    Log.d(
                            TAG,
                            "CALLBACK_NETWORKS_CLEARED");
                }

                @Override
                public void onWifiNetworkFound(
                        String ssid,
                        String bssid,
                        int signalLevel,
                        int securityType,
                        boolean saved,
                        boolean connected) {

                    Log.d(
                            TAG,
                            "CALLBACK_NETWORK_FOUND ssid="
                                    + ssid
                                    + " signal="
                                    + signalLevel
                                    + " security="
                                    + securityType
                                    + " saved="
                                    + saved
                                    + " connected="
                                    + connected);
                }

                @Override
                public void onWifiScanCompleted() {

                    Log.i(
                            TAG,
                            "CALLBACK_SCAN_COMPLETED");

                    postChanged();
                }

                @Override
                public void onWifiConnectionStateChanged(
                        String ssid,
                        int state,
                        int failureReason) {

                    mConnectionSsid =
                            ssid;

                    mConnectionState =
                            state;

                    mFailureReason =
                            failureReason;

                    Log.i(
                            TAG,
                            "CALLBACK_CONNECTION ssid="
                                    + ssid
                                    + " state="
                                    + stateName(state)
                                    + "("
                                    + state
                                    + ")"
                                    + " failure="
                                    + failureName(failureReason)
                                    + "("
                                    + failureReason
                                    + ")");

                    postChanged();
                }

                @Override
                public void onConnectedWifiChanged(
                        String ssid,
                        String bssid,
                        String ipAddress,
                        int signalLevel) {

                    mConnectedSsid =
                            emptyToNull(ssid);

                    mConnectedBssid =
                            emptyToNull(bssid);

                    mConnectedIpAddress =
                            emptyToNull(ipAddress);

                    mConnectedSignalLevel =
                            signalLevel;

                    Log.i(
                            TAG,
                            "CALLBACK_CONNECTED_WIFI ssid="
                                    + ssid
                                    + " bssid="
                                    + bssid
                                    + " ip="
                                    + ipAddress
                                    + " signal="
                                    + signalLevel);

                    postChanged();
                }
            
                @Override
                public void onPhoneConnectionChanged(
                        boolean connected,
                        String deviceName,
                        String deviceAddress) {
                    // HyperNova Settings does not consume phone/HFP state.
                }

};

    void bind() {

        if (mBindRequested
                || mService != null) {

            return;
        }

        Intent intent =
                new Intent(
                        SERVICE_ACTION);

        intent.setPackage(
                SERVICE_PACKAGE);

        Log.i(
                TAG,
                "AIDL_BIND_REQUEST");

        try {

            mBindRequested =
                    mContext.bindService(
                            intent,
                            mConnection,
                            Context.BIND_AUTO_CREATE);

            Log.i(
                    TAG,
                    "AIDL_BIND_ACCEPTED="
                            + mBindRequested);

        } catch (SecurityException exception) {

            mBindRequested =
                    false;

            Log.e(
                    TAG,
                    "AIDL bind rejected by security policy",
                    exception);
        }
    }

    void unbind() {

        IHyperNovaConnectivityService service =
                mService;

        if (service != null) {

            try {

                service.unregisterCallback(
                        mCallback);

            } catch (RemoteException exception) {

                Log.w(
                        TAG,
                        "Unable to unregister callback",
                        exception);
            }
        }

        mService =
                null;

        if (!mBindRequested) {
            return;
        }

        try {

            mContext.unbindService(
                    mConnection);

        } catch (IllegalArgumentException exception) {

            Log.w(
                    TAG,
                    "Service was already unbound",
                    exception);
        }

        mBindRequested =
                false;

        Log.i(
                TAG,
                "AIDL_UNBOUND");
    }

    boolean isConnectedToBackend() {

        return mService != null;
    }

    boolean isWifiEnabled() {

        IHyperNovaConnectivityService service =
                mService;

        if (service == null) {

            return mWifiEnabled;
        }

        try {

            mWifiEnabled =
                    service.isWifiEnabled();

        } catch (RemoteException exception) {

            Log.e(
                    TAG,
                    "isWifiEnabled failed",
                    exception);
        }

        return mWifiEnabled;
    }

    boolean setWifiEnabled(
            boolean enabled) {

        IHyperNovaConnectivityService service =
                mService;

        if (service == null) {

            Log.e(
                    TAG,
                    "SET_WIFI_ENABLED rejected: backend not bound");

            return false;
        }

        try {

            Log.i(
                    TAG,
                    "SET_WIFI_ENABLED enabled="
                            + enabled);

            service.setWifiEnabled(
                    enabled);

            return true;

        } catch (RemoteException exception) {

            Log.e(
                    TAG,
                    "SET_WIFI_ENABLED failed",
                    exception);

            return false;
        }
    }

    boolean requestWifiScan() {

        IHyperNovaConnectivityService service =
                mService;

        if (service == null) {

            Log.e(
                    TAG,
                    "REQUEST_SCAN rejected: backend not bound");

            return false;
        }

        try {

            Log.i(
                    TAG,
                    "REQUEST_SCAN");

            service.requestWifiScan();

            return true;

        } catch (RemoteException exception) {

            Log.e(
                    TAG,
                    "REQUEST_SCAN failed",
                    exception);

            return false;
        }
    }

    void refreshState() {

        IHyperNovaConnectivityService service =
                mService;

        if (service == null) {
            return;
        }

        try {

            service.refreshState();

        } catch (RemoteException exception) {

            Log.e(
                    TAG,
                    "refreshState failed",
                    exception);
        }
    }

    /**
     * Drop-in replacement for WifiManager.connect(configuration, listener).
     *
     * The WifiConfiguration is converted to the AIDL contract and association
     * ownership is transferred to HyperNovaConnectivityService.
     */
    void connect(
            WifiConfiguration configuration,
            @Nullable WifiManager.ActionListener listener) {

        if (configuration == null) {

            notifyActionFailure(
                    listener);

            return;
        }

        IHyperNovaConnectivityService service =
                mService;

        if (service == null) {

            Log.e(
                    TAG,
                    "CONNECT_CONFIG rejected: backend not bound");

            notifyActionFailure(
                    listener);

            return;
        }

        String ssid =
                stripQuotes(
                        configuration.SSID);

        int securityType =
                getSecurityType(
                        configuration);

        String password =
                getCredential(
                        configuration,
                        securityType);

        if (TextUtils.isEmpty(ssid)) {

            Log.e(
                    TAG,
                    "CONNECT_CONFIG rejected: empty SSID");

            notifyActionFailure(
                    listener);

            return;
        }

        if (securityType
                == HyperNovaWifiConstants.SECURITY_UNKNOWN
                || securityType
                == HyperNovaWifiConstants.SECURITY_EAP) {

            Log.e(
                    TAG,
                    "CONNECT_CONFIG rejected: unsupported security ssid="
                            + ssid
                            + " security="
                            + securityType);

            notifyActionFailure(
                    listener);

            return;
        }

        if (requiresCredential(securityType)
                && (TextUtils.isEmpty(password)
                || isMaskedCredential(password))) {

            Log.e(
                    TAG,
                    "CONNECT_CONFIG rejected: missing usable credential ssid="
                            + ssid
                            + " security="
                            + securityType);

            notifyActionFailure(
                    listener);

            return;
        }

        try {

            Log.i(
                    TAG,
                    "CONNECT_CONFIG ssid="
                            + ssid
                            + " security="
                            + securityType
                            + " hidden="
                            + configuration.hiddenSSID);

            service.connectWifi(
                    ssid,
                    password,
                    securityType,
                    configuration.hiddenSSID);

            /*
             * ActionListener success means the connection request was
             * accepted by the backend. Actual association success/failure
             * is delivered asynchronously by AIDL callbacks.
             */
            notifyActionSuccess(
                    listener);

        } catch (RemoteException exception) {

            Log.e(
                    TAG,
                    "CONNECT_CONFIG binder failure ssid="
                            + ssid,
                    exception);

            notifyActionFailure(
                    listener);
        }
    }

    /**
     * Drop-in replacement for WifiEntry.connect(callback).
     *
     * Unsaved networks that do not require an edit/password are normally
     * open networks and are sent directly through AIDL.
     *
     * Saved configurations are converted to the AIDL contract when their
     * credential is available to this privileged Settings process.
     *
     * A narrow compatibility fallback remains for saved framework entries
     * whose secret is intentionally masked by WifiManager. In that case the
     * existing framework-owned WifiEntry is the only object that still owns
     * the credential.
     */
    void connect(
            WifiEntry entry,
            @Nullable WifiEntry.ConnectCallback callback) {

        if (entry == null) {

            notifyConnectFailure(
                    callback);

            return;
        }

        String ssid =
                String.valueOf(
                        entry.getTitle());

        int entrySecurityType =
                getSecurityType(
                        entry);

        WifiConfiguration savedConfiguration =
                findSavedConfiguration(
                        ssid,
                        entrySecurityType);

        if (savedConfiguration != null) {

            int savedSecurityType =
                    getSecurityType(
                            savedConfiguration);

            /*
             * A saved WifiConfiguration can be stale or poisoned.
             *
             * This exact failure was captured on the RPi image: the live
             * WifiEntry was WPA2/PSK while getConfiguredNetworks() returned
             * an old OPEN configuration for the same SSID. Sending that old
             * object through AIDL created an "SSID\"NONE" config and the
             * supplicant could never match the real secured AP.
             *
             * Never trust a saved configuration whose security disagrees
             * with the currently scanned WifiEntry. Force the existing
             * HyperNova password/configuration dialog instead.
             */
            if (!securityTypesCompatible(
                    entrySecurityType,
                    savedSecurityType)) {

                Log.w(
                        TAG,
                        "STALE_SAVED_SECURITY ssid="
                                + ssid
                                + " entrySecurity="
                                + entrySecurityType
                                + " savedSecurity="
                                + savedSecurityType
                                + " -> request fresh configuration");

                notifyConnectNeedsConfiguration(
                        callback);

                return;
            }

            String credential =
                    getCredential(
                            savedConfiguration,
                            savedSecurityType);

            boolean credentialRequired =
                    requiresCredential(
                            savedSecurityType);

            if (!credentialRequired
                    || (!TextUtils.isEmpty(credential)
                    && !isMaskedCredential(credential))) {

                connect(
                        savedConfiguration,
                        new WifiManager.ActionListener() {

                            @Override
                            public void onSuccess() {

                                notifyConnectSuccess(
                                        callback);
                            }

                            @Override
                            public void onFailure(
                                    int reason) {

                                notifyConnectFailure(
                                        callback);
                            }
                        });

                return;
            }

            /*
             * Android may intentionally mask an already-saved credential.
             * We cannot safely invent that secret. The WifiEntry retains the
             * framework-owned saved-network reconnect path, so keep this as
             * the narrow compatibility fallback only after the security type
             * itself has been verified against the live entry.
             */
            Log.w(
                    TAG,
                    "SAVED_ENTRY_CREDENTIAL_MASKED ssid="
                            + ssid
                            + " security="
                            + savedSecurityType
                            + " -> WifiEntry compatibility reconnect");

            entry.connect(
                    callback);

            return;
        }

        /*
         * No saved configuration exists. Do not infer OPEN merely from the
         * absence of a saved config. WifiEntry already knows the security
         * advertised by the current scan. A secured entry must first collect
         * credentials through the existing HyperNova dialog.
         */
        if (entrySecurityType
                != HyperNovaWifiConstants.SECURITY_OPEN) {

            Log.i(
                    TAG,
                    "CONNECT_ENTRY_NEEDS_CONFIG ssid="
                            + ssid
                            + " security="
                            + entrySecurityType);

            notifyConnectNeedsConfiguration(
                    callback);

            return;
        }

        IHyperNovaConnectivityService service =
                mService;

        if (service == null) {

            Log.e(
                    TAG,
                    "CONNECT_ENTRY rejected: backend not bound");

            notifyConnectFailure(
                    callback);

            return;
        }

        try {

            Log.i(
                    TAG,
                    "CONNECT_OPEN_ENTRY ssid="
                            + ssid
                            + " security="
                            + entrySecurityType);

            service.connectWifi(
                    ssid,
                    "",
                    HyperNovaWifiConstants.SECURITY_OPEN,
                    false);

            notifyConnectSuccess(
                    callback);

        } catch (RemoteException exception) {

            Log.e(
                    TAG,
                    "CONNECT_ENTRY binder failure ssid="
                            + ssid,
                    exception);

            notifyConnectFailure(
                    callback);
        }
    }

    /**
     * Drop-in replacement for WifiEntry.disconnect(callback).
     */
    void disconnect(
            @Nullable WifiEntry.DisconnectCallback callback) {

        IHyperNovaConnectivityService service =
                mService;

        if (service == null) {

            notifyDisconnectFailure(
                    callback);

            return;
        }

        try {

            Log.i(
                    TAG,
                    "DISCONNECT_REQUEST");

            service.disconnectWifi();

            notifyDisconnectSuccess(
                    callback);

        } catch (RemoteException exception) {

            Log.e(
                    TAG,
                    "DISCONNECT binder failure",
                    exception);

            notifyDisconnectFailure(
                    callback);
        }
    }

    /**
     * Drop-in replacement for WifiEntry.forget(callback).
     */
    void forget(
            WifiEntry entry,
            @Nullable WifiEntry.ForgetCallback callback) {

        if (entry == null) {

            notifyForgetFailure(
                    callback);

            return;
        }

        IHyperNovaConnectivityService service =
                mService;

        if (service == null) {

            notifyForgetFailure(
                    callback);

            return;
        }

        String ssid =
                String.valueOf(
                        entry.getTitle());

        try {

            Log.i(
                    TAG,
                    "FORGET_REQUEST ssid="
                            + ssid);

            service.forgetWifi(
                    ssid);

            notifyForgetSuccess(
                    callback);

        } catch (RemoteException exception) {

            Log.e(
                    TAG,
                    "FORGET binder failure ssid="
                            + ssid,
                    exception);

            notifyForgetFailure(
                    callback);
        }
    }

    boolean isScanning() {
        return mScanning;
    }

    @Nullable
    String getConnectedSsid() {
        return mConnectedSsid;
    }

    @Nullable
    String getConnectedBssid() {
        return mConnectedBssid;
    }

    @Nullable
    String getConnectedIpAddress() {
        return mConnectedIpAddress;
    }

    int getConnectedSignalLevel() {
        return mConnectedSignalLevel;
    }

    @Nullable
    String getConnectionSsid() {
        return mConnectionSsid;
    }

    int getConnectionState() {
        return mConnectionState;
    }

    int getFailureReason() {
        return mFailureReason;
    }

    @Nullable
    private WifiConfiguration findSavedConfiguration(
            String targetSsid,
            int targetSecurityType) {

        if (mWifiManager == null
                || TextUtils.isEmpty(targetSsid)) {

            return null;
        }

        try {

            List<WifiConfiguration> configurations =
                    mWifiManager.getConfiguredNetworks();

            if (configurations == null) {
                return null;
            }

            WifiConfiguration sameSsidFallback =
                    null;

            for (WifiConfiguration configuration
                    : configurations) {

                if (configuration == null) {
                    continue;
                }

                String configuredSsid =
                        stripQuotes(
                                configuration.SSID);

                if (!targetSsid.equals(
                        configuredSsid)) {

                    continue;
                }

                if (sameSsidFallback == null) {
                    sameSsidFallback =
                            configuration;
                }

                int savedSecurityType =
                        getSecurityType(
                                configuration);

                if (securityTypesCompatible(
                        targetSecurityType,
                        savedSecurityType)) {

                    return configuration;
                }
            }

            /*
             * Returning the same-SSID fallback is intentional. connect(entry)
             * will compare it with the live WifiEntry security and convert the
             * mismatch into CONNECT_STATUS_FAILURE_NO_CONFIG, which opens the
             * HyperNova credential dialog instead of silently treating it as
             * an OPEN network.
             */
            return sameSsidFallback;

        } catch (RuntimeException exception) {

            Log.w(
                    TAG,
                    "Unable to read saved Wi-Fi configurations",
                    exception);
        }

        return null;
    }

    private static int getSecurityType(
            WifiEntry entry) {

        if (entry == null) {

            return HyperNovaWifiConstants
                    .SECURITY_UNKNOWN;
        }

        List<Integer> securityTypes;

        try {

            securityTypes =
                    entry.getSecurityTypes();

        } catch (RuntimeException exception) {

            Log.w(
                    TAG,
                    "Unable to read WifiEntry security for "
                            + entry.getTitle(),
                    exception);

            return HyperNovaWifiConstants
                    .SECURITY_UNKNOWN;
        }

        if (securityTypes == null
                || securityTypes.isEmpty()) {

            return HyperNovaWifiConstants
                    .SECURITY_UNKNOWN;
        }

        boolean hasPsk =
                securityTypes.contains(
                        WifiInfo.SECURITY_TYPE_PSK);

        boolean hasSae =
                securityTypes.contains(
                        WifiInfo.SECURITY_TYPE_SAE);

        if (hasPsk && hasSae) {

            return HyperNovaWifiConstants
                    .SECURITY_WPA2_WPA3_TRANSITION;
        }

        if (hasSae) {

            return HyperNovaWifiConstants
                    .SECURITY_WPA3_SAE;
        }

        if (hasPsk) {

            return HyperNovaWifiConstants
                    .SECURITY_WPA2_PSK;
        }

        if (securityTypes.contains(
                WifiInfo.SECURITY_TYPE_EAP)
                || securityTypes.contains(
                WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE)
                || securityTypes.contains(
                WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT)) {

            return HyperNovaWifiConstants
                    .SECURITY_EAP;
        }

        if (securityTypes.contains(
                WifiInfo.SECURITY_TYPE_WEP)) {

            return HyperNovaWifiConstants
                    .SECURITY_WEP;
        }

        if (securityTypes.contains(
                WifiInfo.SECURITY_TYPE_OPEN)) {

            return HyperNovaWifiConstants
                    .SECURITY_OPEN;
        }

        return HyperNovaWifiConstants
                .SECURITY_UNKNOWN;
    }

    private static int getSecurityType(
            WifiConfiguration configuration) {

        if (configuration == null) {

            return HyperNovaWifiConstants
                    .SECURITY_UNKNOWN;
        }

        /*
         * Baklava's WifiConfiguration class in this RPi tree does not expose
         * WifiConfiguration.isSecurityType(int) to this module.
         *
         * Use the framework-compatible key-management fields instead. The
         * important protection against the stale OPEN configuration remains
         * the comparison with the live WifiEntry security type before this
         * saved configuration is accepted.
         */
        boolean hasSae =
                configuration.allowedKeyManagement != null
                        && configuration.allowedKeyManagement.get(
                        WifiConfiguration.KeyMgmt.SAE);

        boolean hasPsk =
                configuration.allowedKeyManagement != null
                        && configuration.allowedKeyManagement.get(
                        WifiConfiguration.KeyMgmt.WPA_PSK);

        if (hasPsk && hasSae) {

            return HyperNovaWifiConstants
                    .SECURITY_WPA2_WPA3_TRANSITION;
        }

        if (hasSae) {

            return HyperNovaWifiConstants
                    .SECURITY_WPA3_SAE;
        }

        if (hasPsk) {

            return HyperNovaWifiConstants
                    .SECURITY_WPA2_PSK;
        }

        if (configuration.allowedKeyManagement != null
                && (configuration.allowedKeyManagement.get(
                WifiConfiguration.KeyMgmt.WPA_EAP)
                || configuration.allowedKeyManagement.get(
                WifiConfiguration.KeyMgmt.IEEE8021X))) {

            return HyperNovaWifiConstants
                    .SECURITY_EAP;
        }

        if (hasWepCredential(
                configuration)) {

            return HyperNovaWifiConstants
                    .SECURITY_WEP;
        }

        if (configuration.allowedKeyManagement != null
                && configuration.allowedKeyManagement.get(
                WifiConfiguration.KeyMgmt.NONE)) {

            return HyperNovaWifiConstants
                    .SECURITY_OPEN;
        }

        return HyperNovaWifiConstants
                .SECURITY_UNKNOWN;
    }

    private static boolean securityTypesCompatible(
            int liveSecurityType,
            int savedSecurityType) {

        if (liveSecurityType
                == HyperNovaWifiConstants.SECURITY_UNKNOWN
                || savedSecurityType
                == HyperNovaWifiConstants.SECURITY_UNKNOWN) {

            return false;
        }

        if (liveSecurityType
                == savedSecurityType) {

            return true;
        }

        /*
         * A transition-mode AP is compatible with an already-saved PSK or
         * SAE configuration. Do not classify OPEN as compatible with any
         * secured mode.
         */
        if (liveSecurityType
                == HyperNovaWifiConstants.SECURITY_WPA2_WPA3_TRANSITION) {

            return savedSecurityType
                            == HyperNovaWifiConstants.SECURITY_WPA2_PSK
                    || savedSecurityType
                            == HyperNovaWifiConstants.SECURITY_WPA3_SAE;
        }

        if (savedSecurityType
                == HyperNovaWifiConstants.SECURITY_WPA2_WPA3_TRANSITION) {

            return liveSecurityType
                            == HyperNovaWifiConstants.SECURITY_WPA2_PSK
                    || liveSecurityType
                            == HyperNovaWifiConstants.SECURITY_WPA3_SAE;
        }

        return false;
    }

    private static boolean hasWepCredential(
            WifiConfiguration configuration) {

        if (configuration.wepKeys == null) {
            return false;
        }

        for (String key
                : configuration.wepKeys) {

            if (!TextUtils.isEmpty(key)) {
                return true;
            }
        }

        return false;
    }

    private static String getCredential(
            WifiConfiguration configuration,
            int securityType) {

        if (configuration == null) {
            return "";
        }

        switch (securityType) {

            case HyperNovaWifiConstants.SECURITY_WPA2_PSK:
            case HyperNovaWifiConstants.SECURITY_WPA3_SAE:
            case HyperNovaWifiConstants.SECURITY_WPA2_WPA3_TRANSITION:

                return stripQuotes(
                        configuration.preSharedKey);

            case HyperNovaWifiConstants.SECURITY_WEP:

                if (configuration.wepKeys == null
                        || configuration.wepKeys.length == 0) {

                    return "";
                }

                int index =
                        configuration.wepTxKeyIndex;

                if (index < 0
                        || index >= configuration.wepKeys.length) {

                    index = 0;
                }

                return stripQuotes(
                        configuration.wepKeys[index]);

            default:

                return "";
        }
    }

    private static boolean requiresCredential(
            int securityType) {

        return securityType
                == HyperNovaWifiConstants.SECURITY_WEP
                || securityType
                == HyperNovaWifiConstants.SECURITY_WPA2_PSK
                || securityType
                == HyperNovaWifiConstants.SECURITY_WPA3_SAE
                || securityType
                == HyperNovaWifiConstants.SECURITY_WPA2_WPA3_TRANSITION
                || securityType
                == HyperNovaWifiConstants.SECURITY_EAP;
    }

    private static boolean isMaskedCredential(
            String credential) {

        if (TextUtils.isEmpty(credential)) {
            return true;
        }

        for (int index = 0;
                index < credential.length();
                index++) {

            if (credential.charAt(index) != '*') {
                return false;
            }
        }

        return true;
    }

    private static String stripQuotes(
            @Nullable String value) {

        if (value == null) {
            return "";
        }

        String result =
                value.trim();

        if (result.length() >= 2
                && result.charAt(0) == '"'
                && result.charAt(
                result.length() - 1) == '"') {

            result =
                    result.substring(
                            1,
                            result.length() - 1);
        }

        return result;
    }

    @Nullable
    private static String emptyToNull(
            @Nullable String value) {

        if (TextUtils.isEmpty(value)) {
            return null;
        }

        return value;
    }

    private void postChanged() {

        if (mOnChanged == null) {
            return;
        }

        mMainHandler.post(
                () -> {

                    try {

                        mOnChanged.run();

                    } catch (RuntimeException exception) {

                        Log.w(
                                TAG,
                                "UI refresh callback failed",
                                exception);
                    }
                });
    }

    private void notifyActionSuccess(
            @Nullable WifiManager.ActionListener listener) {

        if (listener == null) {
            return;
        }

        mMainHandler.post(
                listener::onSuccess);
    }

    private void notifyActionFailure(
            @Nullable WifiManager.ActionListener listener) {

        if (listener == null) {
            return;
        }

        mMainHandler.post(
                () ->
                        listener.onFailure(
                                -1));
    }

    private void notifyConnectSuccess(
            @Nullable WifiEntry.ConnectCallback callback) {

        if (callback == null) {
            return;
        }

        mMainHandler.post(
                () ->
                        callback.onConnectResult(
                                WifiEntry.ConnectCallback
                                        .CONNECT_STATUS_SUCCESS));
    }

    private void notifyConnectFailure(
            @Nullable WifiEntry.ConnectCallback callback) {

        if (callback == null) {
            return;
        }

        mMainHandler.post(
                () ->
                        callback.onConnectResult(
                                WifiEntry.ConnectCallback
                                        .CONNECT_STATUS_FAILURE_UNKNOWN));
    }

    private void notifyConnectNeedsConfiguration(
            @Nullable WifiEntry.ConnectCallback callback) {

        if (callback == null) {
            return;
        }

        mMainHandler.post(
                () ->
                        callback.onConnectResult(
                                WifiEntry.ConnectCallback
                                        .CONNECT_STATUS_FAILURE_NO_CONFIG));
    }

    private void notifyDisconnectSuccess(
            @Nullable WifiEntry.DisconnectCallback callback) {

        if (callback == null) {
            return;
        }

        mMainHandler.post(
                () ->
                        callback.onDisconnectResult(
                                WifiEntry.DisconnectCallback
                                        .DISCONNECT_STATUS_SUCCESS));
    }

    private void notifyDisconnectFailure(
            @Nullable WifiEntry.DisconnectCallback callback) {

        if (callback == null) {
            return;
        }

        mMainHandler.post(
                () ->
                        callback.onDisconnectResult(
                                WifiEntry.DisconnectCallback
                                        .DISCONNECT_STATUS_FAILURE_UNKNOWN));
    }

    private void notifyForgetSuccess(
            @Nullable WifiEntry.ForgetCallback callback) {

        if (callback == null) {
            return;
        }

        mMainHandler.post(
                () ->
                        callback.onForgetResult(
                                WifiEntry.ForgetCallback
                                        .FORGET_STATUS_SUCCESS));
    }

    private void notifyForgetFailure(
            @Nullable WifiEntry.ForgetCallback callback) {

        if (callback == null) {
            return;
        }

        mMainHandler.post(
                () ->
                        callback.onForgetResult(
                                WifiEntry.ForgetCallback
                                        .FORGET_STATUS_FAILURE_UNKNOWN));
    }

    private static String stateName(
            int state) {

        switch (state) {

            case HyperNovaWifiConstants.CONNECTION_IDLE:
                return "IDLE";

            case HyperNovaWifiConstants.CONNECTION_CONNECTING:
                return "CONNECTING";

            case HyperNovaWifiConstants.CONNECTION_AUTHENTICATING:
                return "AUTHENTICATING";

            case HyperNovaWifiConstants.CONNECTION_OBTAINING_IP:
                return "OBTAINING_IP";

            case HyperNovaWifiConstants.CONNECTION_CONNECTED:
                return "CONNECTED";

            case HyperNovaWifiConstants.CONNECTION_DISCONNECTING:
                return "DISCONNECTING";

            case HyperNovaWifiConstants.CONNECTION_DISCONNECTED:
                return "DISCONNECTED";

            case HyperNovaWifiConstants.CONNECTION_FAILED:
                return "FAILED";

            default:
                return "UNKNOWN_STATE";
        }
    }

    private static String failureName(
            int reason) {

        switch (reason) {

            case HyperNovaWifiConstants.FAILURE_NONE:
                return "NONE";

            case HyperNovaWifiConstants.FAILURE_UNKNOWN:
                return "UNKNOWN";

            case HyperNovaWifiConstants.FAILURE_INVALID_CONFIGURATION:
                return "INVALID_CONFIGURATION";

            case HyperNovaWifiConstants.FAILURE_WRONG_PASSWORD:
                return "WRONG_PASSWORD";

            case HyperNovaWifiConstants.FAILURE_AUTHENTICATION:
                return "AUTHENTICATION";

            case HyperNovaWifiConstants.FAILURE_ASSOCIATION_REJECTED:
                return "ASSOCIATION_REJECTED";

            case HyperNovaWifiConstants.FAILURE_NETWORK_NOT_FOUND:
                return "NETWORK_NOT_FOUND";

            case HyperNovaWifiConstants.FAILURE_DHCP:
                return "DHCP";

            case HyperNovaWifiConstants.FAILURE_FRAMEWORK_REJECTED:
                return "FRAMEWORK_REJECTED";

            case HyperNovaWifiConstants.FAILURE_TIMEOUT:
                return "TIMEOUT";

            case HyperNovaWifiConstants.FAILURE_WIFI_DISABLED:
                return "WIFI_DISABLED";

            case HyperNovaWifiConstants.FAILURE_PERMISSION_DENIED:
                return "PERMISSION_DENIED";

            default:
                return "UNKNOWN_FAILURE";
        }
    }
}
