package com.hypernova.climate.ui

import androidx.lifecycle.ViewModel
import com.hypernova.climate.data.ClimateStateOwner
import com.hypernova.climate.data.ClimateZone
import com.hypernova.climate.model.AirflowMode
import com.hypernova.climate.ui.state.ClimateUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Exposes the immutable [ClimateUiState] the screen renders from (README §36).
 *
 * The application-scoped state owner is seeded per build variant:
 *  - debug   -> a populated sample so the emulator matches the reference,
 *  - release -> an honest empty/unavailable state (no fake data ships).
 *
 * In later phases this flow is driven by the real ClimateBackend state instead
 * of the seeded value; the UI rendering does not change.
 */
class ClimateViewModel : ViewModel() {

    val uiState: StateFlow<ClimateUiState> = ClimateStateOwner.uiState

    /**
     * Cycle the A/C control OFF → COOL → HEAT → OFF.
     *
     * Demo builds confirm through the shared state owner. Release builds reject
     * the mutation until an authoritative backend enables commands.
     */
    fun cycleAcMode() = ClimateStateOwner.cycleAcMode()

    /** Toggle master power through the shared state owner. */
    fun togglePower() = ClimateStateOwner.togglePower()

    /** Toggle AUTO mode. */
    fun toggleAuto() = ClimateStateOwner.toggleAutoMode()

    /** Toggle zone SYNC. */
    fun toggleSync() = ClimateStateOwner.toggleZonesSynchronized()

    fun adjustTargetTemperature(zone: ClimateZone, direction: Int) {
        val state = uiState.value
        val capabilities = state.capabilities ?: return
        val confirmed = state.confirmedState ?: return
        val current = when (zone) {
            ClimateZone.ALL,
            ClimateZone.DRIVER -> confirmed.driverTargetTemperatureC
            ClimateZone.PASSENGER -> confirmed.passengerTargetTemperatureC
        } ?: return
        val step = capabilities.temperatureStepC ?: return
        val minimum = capabilities.minimumTemperatureC ?: return
        val maximum = capabilities.maximumTemperatureC ?: return
        val target = (current + step * direction.coerceIn(-1, 1)).coerceIn(minimum, maximum)
        ClimateStateOwner.setTargetTemperature(zone, target)
    }

    fun adjustFanLevel(direction: Int) {
        val state = uiState.value
        val maximum = state.capabilities?.maximumFanLevel ?: return
        val current = state.confirmedState?.fanLevel ?: return
        ClimateStateOwner.setFanLevel((current + direction.coerceIn(-1, 1)).coerceIn(0, maximum))
    }

    fun toggleRecirculation() = ClimateStateOwner.toggleRecirculation()

    fun enableFreshAir() = ClimateStateOwner.setRecirculationEnabled(false)


    fun setAirflowMode(mode: AirflowMode) =
        ClimateStateOwner.setAirflowMode(mode)

    fun toggleFrontDefrost() =
        ClimateStateOwner.toggleFrontDefrost()

    fun toggleRearDefrost() =
        ClimateStateOwner.toggleRearDefrost()

    fun toggleMaxDefrost() =
        ClimateStateOwner.toggleMaxDefrost()
}
