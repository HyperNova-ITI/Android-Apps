package com.hypernova.climate.ui.state

import com.hypernova.climate.model.AirQualityState
import com.hypernova.climate.model.AirflowMode
import com.hypernova.climate.model.ClimateAvailability
import com.hypernova.climate.model.ClimateCapabilities
import com.hypernova.climate.model.ClimateHealth
import com.hypernova.climate.model.ClimateMode
import com.hypernova.climate.model.ClimateState
import com.hypernova.climate.model.ClimateZoneMode

/**
 * DEBUG-ONLY preview state.
 *
 * Lives only in `src/debug`, so it is compiled into debug builds and never
 * ships in release (which uses the empty `src/release` variant). This lets the
 * emulator render the approved reference look without violating the
 * "no production dummy data" rule (README §5) — the fake values exist solely in
 * the debug variant, comparable to a test double.
 *
 * Values mirror the approved reference image.
 */
object ClimatePreview {

    fun initialUiState(): ClimateUiState {
        val capabilities = ClimateCapabilities(
            zoneMode = ClimateZoneMode.DUAL,
            minimumTemperatureC = 16.0f,
            maximumTemperatureC = 28.0f,
            temperatureStepC = 0.5f,
            maximumFanLevel = 5,
            supportsAutoMode = true,
            supportsAc = true,
            supportsHeating = true,
            supportsZoneSync = true,
            supportedAirflowModes = setOf(
                AirflowMode.FACE,
                AirflowMode.FACE_AND_FEET,
                AirflowMode.FEET,
                AirflowMode.WINDSHIELD
            ),
            supportsFreshAir = true,
            supportsRecirculation = true,
            supportsFrontDefrost = true,
            supportsRearDefrost = true,
            supportsMaxDefrost = true,
            driverSeatHeatingLevels = 3,
            passengerSeatHeatingLevels = 3,
            supportsCabinTemperature = true,
            supportsOutsideTemperature = true,
            supportsAirQuality = true
        )

        val confirmed = ClimateState(
            availability = ClimateAvailability.AVAILABLE,
            health = ClimateHealth.NORMAL,
            powerEnabled = true,
            mode = ClimateMode.AUTO,
            driverTargetTemperatureC = 22.0f,
            passengerTargetTemperatureC = 24.0f,
            cabinTemperatureC = 25.0f,
            outsideTemperatureC = 28.0f,
            airQuality = AirQualityState.GOOD,
            fanLevel = 3,
            acMode = com.hypernova.climate.model.AcMode.COOL,
            autoModeEnabled = true,
            zonesSynchronized = false,
            driverAirflowMode = AirflowMode.FACE,
            passengerAirflowMode = AirflowMode.FACE,
            freshAirEnabled = true,
            recirculationEnabled = false,
            frontDefrostEnabled = false,
            rearDefrostEnabled = false,
            maxDefrostEnabled = false,
            driverSeatHeatingLevel = 0,
            passengerSeatHeatingLevel = 0,
            updatedAtEpochMillis = System.currentTimeMillis()
        )

        return ClimateUiState(
            capabilities = capabilities,
            confirmedState = confirmed,
            requested = null,
            isCommandPending = false,
            canSendCommands = true,
            isStale = false,
            message = null
        )
    }
}
