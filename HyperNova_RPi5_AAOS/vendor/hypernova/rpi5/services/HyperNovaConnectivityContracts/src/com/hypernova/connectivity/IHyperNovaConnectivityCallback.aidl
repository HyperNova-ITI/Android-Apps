package com.hypernova.connectivity;

/**
 * Asynchronous connectivity events delivered to HyperNova UI clients.
 */
oneway interface IHyperNovaConnectivityCallback {

    /**
     * Reports the current Wi-Fi power state.
     */
    void onWifiStateChanged(
            boolean enabled);

    /**
     * Reports whether a scan is currently running.
     */
    void onWifiScanStateChanged(
            boolean scanning);

    /**
     * Clears the previous scan list before a new result batch.
     */
    void onWifiNetworksCleared();

    /**
     * Reports one discovered Wi-Fi network.
     */
    void onWifiNetworkFound(
            String ssid,
            String bssid,
            int signalLevel,
            int securityType,
            boolean saved,
            boolean connected);

    /**
     * Indicates that the current scan result batch is complete.
     */
    void onWifiScanCompleted();

    /**
     * Reports Wi-Fi connection progress or failure.
     *
     * state and failureReason use constants from
     * HyperNovaWifiConstants.
     */
    void onWifiConnectionStateChanged(
            String ssid,
            int state,
            int failureReason);

    /**
     * Reports the currently connected network and IP address.
     */
    void onConnectedWifiChanged(
            String ssid,
            String bssid,
            String ipAddress,
            int signalLevel);

    /**
     * Reports the real AAOS Bluetooth HFP Client connection.
     *
     * connected is true only when HEADSET_CLIENT is actually
     * connected to a remote phone.
     */
    void onPhoneConnectionChanged(
            boolean connected,
            String deviceName,
            String deviceAddress);
}
