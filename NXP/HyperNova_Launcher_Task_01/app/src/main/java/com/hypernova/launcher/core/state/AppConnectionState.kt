package com.hypernova.launcher.core.state

/**
 * Describes the current relationship between the launcher
 * and another HyperNova application or service.
 */
enum class AppConnectionState {

    /**
     * The target application package is not installed.
     */
    NOT_INSTALLED,

    /**
     * The application exists but Android cannot open its screen.
     */
    NO_LAUNCHABLE_ACTIVITY,

    /**
     * The application exists but its service is not connected.
     */
    DISCONNECTED,

    /**
     * The launcher is currently connecting to the service.
     */
    CONNECTING,

    /**
     * The application and its service are ready.
     */
    READY,

    /**
     * The application or service reported an error.
     */
    ERROR
}