package com.hypernova.climate.model

/**
 * What the connected vehicle actually supports (README §34).
 *
 * The UI shows/enables controls strictly from these values — never hard-coded.
 * The final TC397 capabilities are limited to temperature, fan and zone (see
 * IMPLEMENTATION_PLAN.md §1.5). The generic provider model retains richer flags
 * for API compatibility, but this vehicle runtime keeps them false.
 */
data class ClimateCapabilities(
    val zoneMode: ClimateZoneMode,
    val minimumTemperatureC: Float?,
    val maximumTemperatureC: Float?,
    val temperatureStepC: Float?,
    val maximumFanLevel: Int?,
    val supportsAutoMode: Boolean = false,
    val supportsAc: Boolean = false,
    val supportsHeating: Boolean = false,
    val supportsZoneSync: Boolean = false,
    val supportedAirflowModes: Set<AirflowMode> = emptySet(),
    val supportsFreshAir: Boolean = false,
    val supportsRecirculation: Boolean = false,
    val supportsFrontDefrost: Boolean = false,
    val supportsRearDefrost: Boolean = false,
    val supportsMaxDefrost: Boolean = false,
    val driverSeatHeatingLevels: Int = 0,
    val passengerSeatHeatingLevels: Int = 0,
    val supportsCabinTemperature: Boolean = false,
    val supportsOutsideTemperature: Boolean = false,
    val supportsAirQuality: Boolean = false
) {
    val isDualZone: Boolean get() = zoneMode == ClimateZoneMode.DUAL
}
