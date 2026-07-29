package com.hypernova.climate.model

/**
 * Lifecycle of the link to the vehicle backend (Ethernet or VHAL).
 * Kept deliberately small for the UI phase; the full [ClimateState] /
 * [ClimateCapabilities] models are implemented in the domain phase.
 */
enum class ClimateConnectionState {
    /** Not started yet. */
    IDLE,

    /** Attempting to reach the vehicle backend. */
    CONNECTING,

    /** Live link, authoritative state available. */
    CONNECTED,

    /** Link lost; last confirmed state may be stale. */
    DISCONNECTED,

    /** Backend not present on this build/target. */
    UNAVAILABLE
}
