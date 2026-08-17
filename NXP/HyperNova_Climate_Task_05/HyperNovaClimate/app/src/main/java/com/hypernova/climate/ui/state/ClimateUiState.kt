package com.hypernova.climate.ui.state

import com.hypernova.climate.model.ClimateCapabilities
import com.hypernova.climate.model.ClimateState

/**
 * Pending values the user requested but the vehicle has not confirmed yet
 * (README §4, §36). Rendered separately from the confirmed values; the big
 * numbers never switch to these until confirmation.
 */
data class ClimateRequestedState(
    val driverTargetTemperatureC: Float? = null,
    val passengerTargetTemperatureC: Float? = null,
    val fanLevel: Int? = null
)

/**
 * Immutable snapshot the UI renders from (README §36).
 * The screen always renders from [confirmedState]; [requested] is shown as a
 * distinct "requested/pending" hint only.
 */
data class ClimateUiState(
    val capabilities: ClimateCapabilities? = null,
    val confirmedState: ClimateState? = null,
    val requested: ClimateRequestedState? = null,
    val isCommandPending: Boolean = false,
    val canSendCommands: Boolean = false,
    val isStale: Boolean = false,
    val message: String? = null
) {
    val hasData: Boolean get() = confirmedState != null && capabilities != null
}
