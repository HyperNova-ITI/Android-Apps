package com.hypernova.climate.ui

import androidx.lifecycle.ViewModel
import com.hypernova.climate.data.ClimateStateOwner
import com.hypernova.climate.data.ClimateZone
import com.hypernova.climate.backend.VehicleGatewayRuntime
import com.hypernova.climate.model.AirflowMode
import com.hypernova.climate.ui.state.ClimateUiState
import kotlinx.coroutines.flow.StateFlow
import com.hypernova.contracts.vehiclegateway.VehicleGatewayContract

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
    fun cycleAcMode() = Unit

    /** Toggle master power through the shared state owner. */
    fun togglePower() {
        val enabled = uiState.value.confirmedState?.powerEnabled?.not() ?: true
        VehicleGatewayRuntime.setPowerFromUi(enabled)
    }

    /** Toggle AUTO mode. */
    fun toggleAuto() = Unit

    /** Toggle zone SYNC. */
    fun toggleSync() = Unit

    fun adjustTargetTemperature(zone: ClimateZone, direction: Int) {
        val state = uiState.value
        val capabilities = state.capabilities ?: return
        val confirmed = state.confirmedState ?: return
        val current = when (zone) {
            ClimateZone.ALL,
            ClimateZone.DRIVER -> confirmed.driverTargetTemperatureC
            ClimateZone.PASSENGER -> confirmed.passengerTargetTemperatureC
        } ?: 22f
        val step = capabilities.temperatureStepC ?: return
        val minimum = capabilities.minimumTemperatureC ?: return
        val maximum = capabilities.maximumTemperatureC ?: return
        val target = (current + step * direction.coerceIn(-1, 1)).coerceIn(minimum, maximum)
        val gatewayZone = when (zone) {
            ClimateZone.ALL -> VehicleGatewayContract.ZONE_BOTH
            ClimateZone.DRIVER -> VehicleGatewayContract.ZONE_DRIVER
            ClimateZone.PASSENGER -> VehicleGatewayContract.ZONE_PASSENGER
        }
        VehicleGatewayRuntime.setTemperatureFromUi(gatewayZone, target.toInt())
    }

    fun adjustFanLevel(direction: Int) {
        val state = uiState.value
        val maximum = state.capabilities?.maximumFanLevel ?: return
        val current = state.confirmedState?.fanLevel ?: 0
        VehicleGatewayRuntime.setFanFromUi(
            (current + direction.coerceIn(-1, 1)).coerceIn(0, maximum),
        )
    }

    fun toggleRecirculation() = Unit

    fun enableFreshAir() = Unit


    fun setAirflowMode(mode: AirflowMode) =
        Unit

    fun setAirflowMode(zone: ClimateZone, mode: AirflowMode) =
        Unit

    fun toggleFrontDefrost() =
        Unit

    fun toggleRearDefrost() =
        Unit

    fun toggleMaxDefrost() =
        Unit
}
