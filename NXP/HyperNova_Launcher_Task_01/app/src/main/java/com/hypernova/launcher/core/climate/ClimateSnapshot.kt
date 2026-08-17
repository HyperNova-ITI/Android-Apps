package com.hypernova.launcher.core.climate

import com.hypernova.launcher.core.state.AppConnectionState

enum class ClimateAvailability {
    UNAVAILABLE,
    AVAILABLE,
    STALE,
}

enum class ClimateStateSource {
    NONE,
    CONTRACT,
}

/** Authoritative read-only climate values. Null means the value was unavailable. */
data class ClimateSnapshot(
    val connectionState: AppConnectionState,
    val availability: ClimateAvailability = ClimateAvailability.UNAVAILABLE,
    val source: ClimateStateSource = ClimateStateSource.NONE,
    val powerEnabled: Boolean? = null,
    val driverTargetTemperatureC: Float? = null,
    val fanLevel: Int? = null,
    val acEnabled: Boolean? = null,
    val autoModeEnabled: Boolean? = null,
    val errorMessage: String? = null,
)
