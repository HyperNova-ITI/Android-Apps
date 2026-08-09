/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.hypernova.connectivity;

/**
 * Shared Wi-Fi constants used by the HyperNova connectivity service
 * and all HyperNova client applications.
 */
public final class HyperNovaWifiConstants {

    public static final int CONTRACT_VERSION = 2;

    /*
     * Wi-Fi security types.
     */
    public static final int SECURITY_OPEN = 0;
    public static final int SECURITY_WEP = 1;
    public static final int SECURITY_WPA2_PSK = 2;
    public static final int SECURITY_WPA3_SAE = 3;
    public static final int SECURITY_WPA2_WPA3_TRANSITION = 4;
    public static final int SECURITY_EAP = 5;
    public static final int SECURITY_UNKNOWN = 100;

    /*
     * Connection states.
     */
    public static final int CONNECTION_IDLE = 0;
    public static final int CONNECTION_CONNECTING = 1;
    public static final int CONNECTION_AUTHENTICATING = 2;
    public static final int CONNECTION_OBTAINING_IP = 3;
    public static final int CONNECTION_CONNECTED = 4;
    public static final int CONNECTION_DISCONNECTING = 5;
    public static final int CONNECTION_DISCONNECTED = 6;
    public static final int CONNECTION_FAILED = 7;

    /*
     * Connection failure reasons.
     */
    public static final int FAILURE_NONE = 0;
    public static final int FAILURE_UNKNOWN = 1;
    public static final int FAILURE_INVALID_CONFIGURATION = 2;
    public static final int FAILURE_WRONG_PASSWORD = 3;
    public static final int FAILURE_AUTHENTICATION = 4;
    public static final int FAILURE_ASSOCIATION_REJECTED = 5;
    public static final int FAILURE_NETWORK_NOT_FOUND = 6;
    public static final int FAILURE_DHCP = 7;
    public static final int FAILURE_FRAMEWORK_REJECTED = 8;
    public static final int FAILURE_TIMEOUT = 9;
    public static final int FAILURE_WIFI_DISABLED = 10;
    public static final int FAILURE_PERMISSION_DENIED = 11;

    private HyperNovaWifiConstants() {
    }
}
