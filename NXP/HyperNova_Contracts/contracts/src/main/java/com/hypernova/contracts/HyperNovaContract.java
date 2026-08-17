package com.hypernova.contracts;

/** Frozen cross-APK constants for the HyperNova demo contract, API version 1. */
public final class HyperNovaContract {
    public static final int API_VERSION = 1;

    public static final String CONTROL_PERMISSION =
            "com.hypernova.permission.CONTROL_COCKPIT_APPS";

    public static final int STATUS_ACCEPTED = 1;
    public static final int STATUS_CONFIRMED = 2;
    public static final int STATUS_REJECTED = 3;
    public static final int STATUS_UNAVAILABLE = 4;
    public static final int STATUS_TIMEOUT = 5;
    public static final int STATUS_CANCELLED = 6;

    public static final long REQUEST_DEDUP_TTL_MILLIS = 10 * 60 * 1000L;

    public static final String ERROR_NONE = "";
    public static final String ERROR_INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String ERROR_UNSUPPORTED_OPERATION = "UNSUPPORTED_OPERATION";
    public static final String ERROR_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String ERROR_BUSY = "BUSY";
    public static final String ERROR_TIMEOUT = "TIMEOUT";
    public static final String ERROR_PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String ERROR_INTERNAL = "INTERNAL_ERROR";

    private HyperNovaContract() {
    }
}
