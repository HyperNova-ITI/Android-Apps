package com.hypernova.contracts.climate;

/** Frozen Climate demo contract, API version 1. */
public final class ClimateContract {
    public static final String PACKAGE_NAME = "com.hypernova.climate";
    public static final String OPEN_ACTION = "com.hypernova.climate.action.OPEN";
    public static final String COMMAND_SERVICE =
            "com.hypernova.climate.service.ClimateCommandService";
    public static final String BIND_COMMAND_ACTION =
            "com.hypernova.climate.action.BIND_COMMAND";

    public static final String OP_GET_CAPABILITIES = "get_capabilities";
    public static final String OP_GET_CURRENT_STATE = "get_current_state";
    public static final String OP_SET_POWER = "set_power";
    public static final String OP_SET_TEMPERATURE = "set_temperature";
    public static final String OP_SET_FAN_LEVEL = "set_fan_level";
    public static final String OP_SET_AC = "set_ac";
    public static final String OP_SET_AUTO = "set_auto";
    public static final String OP_SET_RECIRCULATION = "set_recirculation";

    public static final int ZONE_ALL = 0;
    public static final int ZONE_DRIVER = 1;
    public static final int ZONE_PASSENGER = 2;

    public static final int ZONE_MODE_SINGLE = 1;
    public static final int ZONE_MODE_DUAL = 2;

    public static final int AVAILABILITY_UNAVAILABLE = 0;
    public static final int AVAILABILITY_AVAILABLE = 1;
    public static final int AVAILABILITY_STALE = 2;

    public static final long QUERY_TIMEOUT_MILLIS = 2 * 1000L;
    public static final long COMMAND_TIMEOUT_MILLIS = 5 * 1000L;

    public static final String ERROR_HARDWARE_REJECTED = "HARDWARE_REJECTED";
    public static final String ERROR_UNSUPPORTED_ZONE = "UNSUPPORTED_ZONE";
    public static final String ERROR_OUT_OF_RANGE = "OUT_OF_RANGE";

    private ClimateContract() {
    }
}
