package com.hypernova.connectivity;

import com.hypernova.connectivity.IHyperNovaConnectivityCallback;

/**
 * HyperNova connectivity backend contract.
 *
 * HyperNova applications use this privileged backend for platform
 * connectivity state instead of duplicating framework integration.
 */
interface IHyperNovaConnectivityService {

    /**
     * Returns the current contract version.
     */
    int getContractVersion();

    /**
     * Registers a client callback.
     */
    void registerCallback(
            IHyperNovaConnectivityCallback callback);

    /**
     * Removes a previously registered callback.
     */
    void unregisterCallback(
            IHyperNovaConnectivityCallback callback);

    /**
     * Returns true when Android Wi-Fi is enabled.
     */
    boolean isWifiEnabled();

    /**
     * Enables or disables Wi-Fi.
     */
    void setWifiEnabled(
            boolean enabled);

    /**
     * Requests a new Wi-Fi scan.
     */
    void requestWifiScan();

    /**
     * Connects to a Wi-Fi network.
     *
     * securityType uses constants from HyperNovaWifiConstants.
     */
    void connectWifi(
            String ssid,
            String password,
            int securityType,
            boolean hidden);

    /**
     * Disconnects from the current Wi-Fi network.
     */
    void disconnectWifi();

    /**
     * Removes a saved Wi-Fi configuration.
     */
    void forgetWifi(
            String ssid);

    /**
     * Returns true only when the AAOS Bluetooth HFP Client profile
     * currently has a connected phone.
     */
    boolean isHfpConnected();

    /**
     * Returns the connected HFP phone name, or an empty string.
     */
    String getHfpDeviceName();

    /**
     * Returns the connected HFP phone Bluetooth address,
     * or an empty string.
     */
    String getHfpDeviceAddress();

    /**
     * Requests the backend to send its current state to callbacks.
     */
    void refreshState();
}
