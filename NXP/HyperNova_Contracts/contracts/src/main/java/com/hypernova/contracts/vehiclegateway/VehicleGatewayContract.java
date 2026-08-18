package com.hypernova.contracts.vehiclegateway;

/** Frozen Android/QNX vehicle-gateway constants for the HyperNova demo. */
public final class VehicleGatewayContract {
    public static final int API_VERSION = 1;

    public static final String PACKAGE_NAME = "com.hypernova.vehiclegateway";
    public static final String COMMAND_SERVICE =
            "com.hypernova.vehiclegateway.VehicleGatewayService";
    public static final String BIND_ACTION =
            "com.hypernova.vehiclegateway.action.BIND_GATEWAY";

    public static final int CONNECTION_DISCONNECTED = 0;
    public static final int CONNECTION_CONNECTING = 1;
    public static final int CONNECTION_CONNECTED = 2;
    public static final int CONNECTION_DEGRADED = 3;

    public static final int ZONE_BOTH = 0;
    public static final int ZONE_DRIVER = 1;
    public static final int ZONE_PASSENGER = 2;

    public static final int CALLER_DRIVER = 0;
    public static final int CALLER_AI = 1;

    public static final int MIN_TARGET_TEMPERATURE_C = 16;
    public static final int MAX_TARGET_TEMPERATURE_C = 28;
    public static final int MIN_FAN_LEVEL = 0;
    public static final int MAX_FAN_LEVEL = 5;

    public static final int DTC_P0217 = 0x0217;
    public static final int DTC_P0118 = 0x0118;
    public static final int DTC_P0300 = 0x0300;
    public static final int DTC_P0442 = 0x0442;
    public static final int DTC_P0562 = 0x0562;

    public static final String ERROR_NONE = "";
    public static final String ERROR_INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String ERROR_BUSY = "BUSY";
    public static final String ERROR_TIMEOUT = "TIMEOUT";
    public static final String ERROR_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String ERROR_PROTOCOL = "PROTOCOL_ERROR";
    public static final String ERROR_TC397_UNKNOWN_COMMAND = "TC397_UNKNOWN_COMMAND";
    public static final String ERROR_TC397_INVALID_LENGTH = "TC397_INVALID_LENGTH";
    public static final String ERROR_TC397_INVALID_PARAMETER = "TC397_INVALID_PARAMETER";
    public static final String ERROR_TC397_SAFETY_BLOCKED = "TC397_SAFETY_BLOCKED";
    public static final String ERROR_TC397_SYSTEM_FAULT = "TC397_SYSTEM_FAULT";
    public static final String ERROR_TC397_HARDWARE_FAULT = "TC397_HARDWARE_FAULT";
    public static final String ERROR_TC397_OVERHEAT = "TC397_OVERHEAT";
    public static final String ERROR_TC397_SENSOR_FAULT = "TC397_SENSOR_FAULT";
    public static final String ERROR_TC397_NOT_READY = "TC397_NOT_READY";

    /** Android guard timeout; QNX owns the 5 s TC397 timeout and reports first. */
    public static final long COMMAND_TIMEOUT_MILLIS = 6_500L;
    public static final long TELEMETRY_FRESH_MILLIS = 3_000L;

    private VehicleGatewayContract() {
    }
}
