package com.hypernova.climate.data

import com.hypernova.climate.model.AcMode
import com.hypernova.climate.model.AirflowMode
import com.hypernova.climate.model.ClimateAvailability
import com.hypernova.climate.model.ClimateMode
import com.hypernova.climate.model.ClimateState
import com.hypernova.climate.ui.state.ClimatePreview
import com.hypernova.climate.ui.state.ClimateRequestedState
import com.hypernova.climate.ui.state.ClimateUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cabin zones understood by the in-process Climate state owner. */
enum class ClimateZone {
    ALL,
    DRIVER,
    PASSENGER,
}

/**
 * The one authoritative Climate state source inside the Climate process.
 *
 * The debug/demo source set seeds this owner from [ClimatePreview]. The release
 * source set starts unavailable and remains honest until a real backend calls
 * [publishBackendState]. Both the screen ViewModel and the exported AIDL service
 * read and mutate this same owner.
 */
object ClimateStateOwner {
    private val lock = Any()
    private val mutableState = MutableStateFlow(ClimatePreview.initialUiState())

    val uiState: StateFlow<ClimateUiState> = mutableState.asStateFlow()

    fun currentState(): ClimateUiState = mutableState.value

    /** Entry point for a future authoritative vehicle backend/readback. */
    fun publishBackendState(state: ClimateUiState) {
        mutableState.value = state
    }

    fun markPending(requested: ClimateRequestedState, message: String) {
        synchronized(lock) {
            mutableState.value = mutableState.value.copy(
                requested = requested,
                isCommandPending = true,
                message = message,
            )
        }
    }

    fun finishPending(message: String) {
        synchronized(lock) {
            mutableState.value = mutableState.value.copy(
                requested = null,
                isCommandPending = false,
                message = message,
            )
        }
    }

    fun togglePower(): Boolean {
        val enabled = currentState().confirmedState?.powerEnabled?.not() ?: return false
        return setPowerEnabled(enabled)
    }

    fun setPowerEnabled(enabled: Boolean): Boolean = mutateConfirmed { state ->
        state.copy(
            powerEnabled = enabled,
            mode = when {
                !enabled -> ClimateMode.OFF
                state.autoModeEnabled == true -> ClimateMode.AUTO
                else -> ClimateMode.MANUAL
            },
        )
    }

    fun setTargetTemperature(zone: ClimateZone, temperatureC: Float): Boolean =
        mutateConfirmed { state ->
            val synchronized = state.zonesSynchronized == true
            val dualZone = currentState().capabilities?.isDualZone == true
            when (zone) {
                ClimateZone.ALL -> state.copy(
                    powerEnabled = true,
                    driverTargetTemperatureC = temperatureC,
                    passengerTargetTemperatureC = if (dualZone) {
                        temperatureC
                    } else {
                        state.passengerTargetTemperatureC
                    },
                    mode = activeMode(state),
                )
                ClimateZone.DRIVER -> state.copy(
                    driverTargetTemperatureC = temperatureC,
                    passengerTargetTemperatureC = if (synchronized) {
                        temperatureC
                    } else {
                        state.passengerTargetTemperatureC
                    },
                )
                ClimateZone.PASSENGER -> state.copy(
                    driverTargetTemperatureC = if (synchronized) {
                        temperatureC
                    } else {
                        state.driverTargetTemperatureC
                    },
                    passengerTargetTemperatureC = temperatureC,
                )
            }
        }

    fun setFanLevel(level: Int): Boolean = mutateConfirmed { state ->
        state.copy(fanLevel = level)
    }

    fun cycleAcMode(): Boolean = mutateConfirmed { state ->
        val next = when (state.acMode) {
            AcMode.OFF -> AcMode.COOL
            AcMode.COOL -> AcMode.HEAT
            AcMode.HEAT -> AcMode.OFF
        }
        state.copy(acMode = next)
    }

    fun setAcEnabled(enabled: Boolean): Boolean = mutateConfirmed { state ->
        state.copy(
            acMode = when {
                !enabled -> AcMode.OFF
                state.acMode == AcMode.OFF -> AcMode.COOL
                else -> state.acMode
            },
        )
    }

    fun setAutoModeEnabled(enabled: Boolean): Boolean = mutateConfirmed { state ->
        state.copy(
            autoModeEnabled = enabled,
            mode = when {
                !state.powerEnabled -> ClimateMode.OFF
                enabled -> ClimateMode.AUTO
                else -> ClimateMode.MANUAL
            },
        )
    }

    fun toggleAutoMode(): Boolean {
        val enabled = currentState().confirmedState?.autoModeEnabled?.not() ?: return false
        return setAutoModeEnabled(enabled)
    }

    fun setRecirculationEnabled(enabled: Boolean): Boolean = mutateConfirmed { state ->
        state.copy(
            recirculationEnabled = enabled,
            freshAirEnabled = !enabled,
        )
    }

    fun toggleRecirculation(): Boolean {
        val enabled = currentState().confirmedState?.recirculationEnabled?.not() ?: return false
        return setRecirculationEnabled(enabled)
    }

    fun setZonesSynchronized(enabled: Boolean): Boolean = mutateConfirmed { state ->
        state.copy(
            zonesSynchronized = enabled,
            passengerTargetTemperatureC = if (enabled) {
                state.driverTargetTemperatureC
            } else {
                state.passengerTargetTemperatureC
            },
        )
    }

    fun toggleZonesSynchronized(): Boolean {
        val enabled = currentState().confirmedState?.zonesSynchronized?.not() ?: return false
        return setZonesSynchronized(enabled)
    }

    /**
     * Select cabin airflow direction.
     *
     * ClimateState currently has one airflowMode, so Driver and Passenger
     * selectors control the same confirmed cabin airflow mode.
     */
    fun setAirflowMode(zone: ClimateZone, mode: AirflowMode): Boolean {
        val capabilities = currentState().capabilities ?: return false

        if (mode !in capabilities.supportedAirflowModes) {
            return false
        }

        return mutateConfirmed { state ->
            when (zone) {
                ClimateZone.ALL -> state.copy(
                    airflowMode = mode,
                    driverAirflowMode = mode,
                    passengerAirflowMode = mode,
                    powerEnabled = true,
                )

                ClimateZone.DRIVER -> state.copy(
                    driverAirflowMode = mode,
                    powerEnabled = true,
                )

                ClimateZone.PASSENGER -> state.copy(
                    passengerAirflowMode = mode,
                    powerEnabled = true,
                )
            }
        }
    }

    fun setAirflowMode(mode: AirflowMode): Boolean =
        setAirflowMode(ClimateZone.ALL, mode)

    fun toggleFrontDefrost(): Boolean {
        if (currentState().capabilities?.supportsFrontDefrost != true) {
            return false
        }

        return mutateConfirmed { state ->
            val enabled = !(state.frontDefrostEnabled ?: false)

            state.copy(
                frontDefrostEnabled = enabled,
                powerEnabled = if (enabled) true else state.powerEnabled,
            )
        }
    }

    fun toggleRearDefrost(): Boolean {
        if (currentState().capabilities?.supportsRearDefrost != true) {
            return false
        }

        return mutateConfirmed { state ->
            state.copy(
                rearDefrostEnabled = !(state.rearDefrostEnabled ?: false),
            )
        }
    }

    fun toggleMaxDefrost(): Boolean {
        if (currentState().capabilities?.supportsMaxDefrost != true) {
            return false
        }

        return mutateConfirmed { state ->
            val enabled = !(state.maxDefrostEnabled ?: false)

            state.copy(
                maxDefrostEnabled = enabled,
                powerEnabled = if (enabled) true else state.powerEnabled,
            )
        }
    }

    private fun mutateConfirmed(transform: (ClimateState) -> ClimateState): Boolean =
        synchronized(lock) {
            val uiState = mutableState.value
            val confirmed = uiState.confirmedState
            if (
                !uiState.canSendCommands ||
                confirmed == null ||
                confirmed.availability != ClimateAvailability.AVAILABLE
            ) {
                return@synchronized false
            }

            mutableState.value = uiState.copy(
                confirmedState = transform(confirmed).copy(
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
                requested = null,
                isCommandPending = false,
                isStale = false,
            )
            true
        }

    private fun activeMode(state: ClimateState): ClimateMode =
        if (state.autoModeEnabled == true) ClimateMode.AUTO else ClimateMode.MANUAL
}
