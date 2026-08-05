package com.hypernova.ai.command

import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.climate.ClimateCapabilities
import com.hypernova.contracts.climate.ClimateResult
import com.hypernova.contracts.climate.ClimateState
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationDestination
import com.hypernova.contracts.navigation.NavigationResult

object AndroidResultMapper {
    fun navigation(request: CommandRequest, result: NavigationResult?): CommandResult {
        if (result == null) return invalidProviderResult(request)
        if (result.requestId != request.requestId || result.operation != request.operation) {
            return invalidProviderResult(request)
        }
        val status = CommandStatus.fromContract(result.status) ?: return invalidProviderResult(request)
        val data = linkedMapOf<String, Any?>()
        if (result.destinations.isNotEmpty()) {
            data["destinations"] = result.destinations
                .take(NavigationContract.MAX_DESTINATION_RESULTS)
                .map { it.toWireMap() }
        }
        result.selectedDestination?.let { data["selected_destination"] = it.toWireMap() }
        if (result.navigationState > 0) {
            data["navigation_state"] = navigationState(result.navigationState)
        }
        if (result.etaSeconds >= 0) data["eta_seconds"] = result.etaSeconds
        if (result.distanceMeters >= 0) data["distance_meters"] = result.distanceMeters
        return CommandResult(
            request = request,
            status = status,
            message = result.message.orEmpty().ifBlank { status.wireValue },
            errorCode = result.errorCode?.takeIf(String::isNotBlank),
            data = data,
        )
    }

    fun climate(request: CommandRequest, result: ClimateResult?): CommandResult {
        if (result == null) return invalidProviderResult(request)
        if (result.requestId != request.requestId || result.operation != request.operation) {
            return invalidProviderResult(request)
        }
        val status = CommandStatus.fromContract(result.status) ?: return invalidProviderResult(request)
        val data = linkedMapOf<String, Any?>()
        result.capabilities?.let { data["capabilities"] = it.toWireMap() }
        result.confirmedState?.let { data["confirmed_state"] = it.toWireMap() }
        return CommandResult(
            request = request,
            status = status,
            message = result.message.orEmpty().ifBlank { status.wireValue },
            errorCode = result.errorCode?.takeIf(String::isNotBlank),
            data = data,
        )
    }

    private fun invalidProviderResult(request: CommandRequest): CommandResult = request.failure(
        status = CommandStatus.UNAVAILABLE,
        message = "${request.domain.replaceFirstChar(Char::uppercase)} returned an invalid result",
        errorCode = HyperNovaContract.ERROR_INTERNAL,
    )

    private fun NavigationDestination.toWireMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "source" to sourceName(source),
        "title" to title,
        "subtitle" to subtitle,
        "category" to category,
        "distance_meters" to distanceMeters.takeIf { it >= 0 },
    ).filterValues { it != null }

    private fun ClimateCapabilities.toWireMap(): Map<String, Any?> = linkedMapOf(
        "zone_mode" to if (zoneMode == 2) "dual" else "single",
        "minimum_temperature_c" to minimumTemperatureC,
        "maximum_temperature_c" to maximumTemperatureC,
        "temperature_step_c" to temperatureStepC,
        "maximum_fan_level" to maximumFanLevel,
        "supports_power" to supportsPower(),
        "supports_temperature" to supportsTemperature(),
        "supports_ac" to supportsAc(),
        "supports_auto" to supportsAuto(),
        "supports_recirculation" to supportsRecirculation(),
    )

    private fun ClimateState.toWireMap(): Map<String, Any?> = linkedMapOf(
        "availability" to when (availability) {
            1 -> "available"
            2 -> "stale"
            else -> "unavailable"
        },
        "power_enabled" to isPowerEnabled,
        "driver_temperature_c" to driverTargetTemperatureC.takeUnless(Float::isNaN),
        "passenger_temperature_c" to passengerTargetTemperatureC.takeUnless(Float::isNaN),
        "fan_level" to fanLevel.takeIf { it >= 0 },
        "ac_enabled" to isAcEnabled,
        "auto_enabled" to isAutoModeEnabled,
        "recirculation_enabled" to isRecirculationEnabled,
        "updated_at_epoch_millis" to updatedAtEpochMillis,
    ).filterValues { it != null }

    private fun sourceName(source: Int): String = when (source) {
        NavigationContract.SOURCE_SEARCH -> "search"
        NavigationContract.SOURCE_SAVED_HOME -> "saved_home"
        NavigationContract.SOURCE_SAVED_WORK -> "saved_work"
        NavigationContract.SOURCE_SAVED_FAVORITE -> "saved_favorite"
        else -> "unknown"
    }

    private fun navigationState(state: Int): String = when (state) {
        NavigationContract.STATE_IDLE -> "IDLE"
        NavigationContract.STATE_CALCULATING -> "CALCULATING"
        NavigationContract.STATE_ACTIVE -> "ACTIVE"
        NavigationContract.STATE_ARRIVED -> "ARRIVED"
        else -> "ERROR"
    }
}
