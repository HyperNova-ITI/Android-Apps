package com.hypernova.contracts.navigation;

/** Navigation demo API v1 with an additive read-only status extension. */
public final class NavigationContract {
    public static final String PACKAGE_NAME = "com.hypernova.navigation";
    public static final String OPEN_ACTION = "com.hypernova.navigation.action.OPEN";
    public static final String COMMAND_SERVICE =
            "com.hypernova.navigation.service.NavigationCommandService";
    public static final String BIND_COMMAND_ACTION =
            "com.hypernova.navigation.action.BIND_COMMAND";

    public static final String OP_SEARCH_DESTINATIONS = "search_destinations";
    public static final String OP_GET_SAVED_DESTINATIONS = "get_saved_destinations";
    public static final String OP_SET_DESTINATION = "set_destination";
    public static final String OP_CANCEL_NAVIGATION = "cancel_navigation";
    public static final String OP_GET_CURRENT_STATE = "get_current_state";
    public static final String OP_GET_ROUTE_PREVIEW = "get_route_preview";

    public static final int MAX_DESTINATION_RESULTS = 4;
    public static final long SEARCH_RESULT_TTL_MILLIS = 10 * 60 * 1000L;
    public static final long SEARCH_TIMEOUT_MILLIS = 10 * 1000L;
    public static final long ROUTE_TIMEOUT_MILLIS = 20 * 1000L;
    public static final int MAX_ROUTE_PREVIEW_POINTS = 128;
    /** Maximum frequency for lightweight cross-process progress updates. */
    public static final long MIN_PROGRESS_UPDATE_INTERVAL_MILLIS = 1_000L;

    public static final int SOURCE_SEARCH = 1;
    public static final int SOURCE_SAVED_HOME = 2;
    public static final int SOURCE_SAVED_WORK = 3;
    public static final int SOURCE_SAVED_FAVORITE = 4;

    public static final int STATE_IDLE = 1;
    public static final int STATE_CALCULATING = 2;
    public static final int STATE_ACTIVE = 3;
    public static final int STATE_ARRIVED = 4;
    public static final int STATE_ERROR = 5;

    public static final String ERROR_NO_RESULTS = "NO_RESULTS";
    public static final String ERROR_NO_SAVED_DESTINATIONS = "NO_SAVED_DESTINATIONS";
    public static final String ERROR_DESTINATION_EXPIRED = "DESTINATION_EXPIRED";
    public static final String ERROR_DESTINATION_NOT_FOUND = "DESTINATION_NOT_FOUND";
    public static final String ERROR_LOCATION_UNAVAILABLE = "LOCATION_UNAVAILABLE";
    public static final String ERROR_ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND";
    public static final String ERROR_OFFLINE_DATA_UNAVAILABLE = "OFFLINE_DATA_UNAVAILABLE";

    private NavigationContract() {
    }
}
