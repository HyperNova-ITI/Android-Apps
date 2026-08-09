/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.android.car.settings.hypernova;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

/**
 * Holds a generic Wi-Fi Internet transport request while HyperNova Settings is
 * completing a framework-owned saved-network connection.
 *
 * <p>The request deliberately does not use {@code WifiNetworkSpecifier}; the
 * real saved configuration and association remain owned by WifiManager,
 * WifiTrackerLib, WifiService and wpa_supplicant.</p>
 *
 * <p>The Settings fragment already owns the user-visible connection timeout
 * and always calls {@link #release()} on success, failure, timeout and
 * teardown. Therefore this helper uses the public two-argument
 * {@code requestNetwork()} overload and avoids a second competing timeout.</p>
 */
public final class HyperNovaWifiTransportRequest {

    private static final String TAG = "HyperNovaWifiRequest";
    private static final Object LOCK = new Object();
    private static final long DEFAULT_CONNECTION_TIMEOUT_MS = 45_000L;

    private static ConnectivityManager sConnectivityManager;
    private static ConnectivityManager.NetworkCallback sNetworkCallback;

    private HyperNovaWifiTransportRequest() {
    }

    /**
     * Source-compatible entry point used by existing Settings call sites.
     * The fragment owns the visible timeout; this default is retained only to
     * validate the request through the shared implementation below.
     */
    public static boolean acquire(Context context) {
        return acquire(context, DEFAULT_CONNECTION_TIMEOUT_MS);
    }

    /**
     * Acquire one idempotent generic Wi-Fi request.
     *
     * @param context any valid application or component context
     * @param connectionTimeoutMs retained for source compatibility; the owning
     *        Settings fragment enforces this timeout and releases the request
     * @return true when the request is active or was already active
     */
    public static boolean acquire(Context context, long connectionTimeoutMs) {
        if (context == null || connectionTimeoutMs <= 0L) {
            return false;
        }

        synchronized (LOCK) {
            if (sNetworkCallback != null) {
                return true;
            }

            Context applicationContext = context.getApplicationContext();
            ConnectivityManager connectivityManager =
                    applicationContext.getSystemService(ConnectivityManager.class);
            if (connectivityManager == null) {
                Log.e(TAG, "ConnectivityManager is unavailable");
                return false;
            }

            NetworkRequest request =
                    new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build();

            ConnectivityManager.NetworkCallback callback =
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            Log.i(TAG, "Wi-Fi transport available: " + network);
                        }

                        @Override
                        public void onLost(Network network) {
                            Log.i(TAG, "Wi-Fi transport lost: " + network);
                        }

                        @Override
                        public void onUnavailable() {
                            Log.w(TAG, "Wi-Fi transport unavailable");
                        }
                    };

            try {
                connectivityManager.requestNetwork(request, callback);
                sConnectivityManager = connectivityManager;
                sNetworkCallback = callback;
                Log.i(TAG, "Generic Wi-Fi transport request acquired");
                return true;
            } catch (RuntimeException exception) {
                Log.e(TAG, "Unable to request generic Wi-Fi transport", exception);
                return false;
            }
        }
    }

    /** Release the active request. Calling this repeatedly is safe. */
    public static void release() {
        final ConnectivityManager connectivityManager;
        final ConnectivityManager.NetworkCallback callback;

        synchronized (LOCK) {
            connectivityManager = sConnectivityManager;
            callback = sNetworkCallback;
            sConnectivityManager = null;
            sNetworkCallback = null;
        }

        if (connectivityManager == null || callback == null) {
            return;
        }

        try {
            connectivityManager.unregisterNetworkCallback(callback);
            Log.i(TAG, "Generic Wi-Fi transport request released");
        } catch (IllegalArgumentException exception) {
            Log.w(TAG, "Wi-Fi transport request was already released", exception);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to release Wi-Fi transport request cleanly", exception);
        }
    }
}
