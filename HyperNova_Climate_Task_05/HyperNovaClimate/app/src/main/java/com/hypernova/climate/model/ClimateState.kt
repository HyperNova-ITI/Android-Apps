package com.hypernova.climate.model

/**
 * The authoritative, vehicle-confirmed climate state (README §35).
 * Nullable fields mean "not available / unknown" — the UI renders an honest
 * unavailable state rather than substituting a number.
 */
data class ClimateState(
    val availability: ClimateAvailability,
    val health: ClimateHealth,
    val powerEnabled: Boolean,
    val mode: ClimateMode,
    val driverTargetTemperatureC: Float? = null,
    val passengerTargetTemperatureC: Float? = null,
    val cabinTemperatureC: Float? = null,
    val outsideTemperatureC: Float? = null,
    val airQuality: AirQualityState? = null,
    val fanLevel: Int? = null,
    val acMode: AcMode = AcMode.OFF,
    val autoModeEnabled: Boolean? = null,
    val zonesSynchronized: Boolean? = null,
    val airflowMode: AirflowMode? = null,
    val freshAirEnabled: Boolean? = null,
    val recirculationEnabled: Boolean? = null,
    val frontDefrostEnabled: Boolean? = null,
    val rearDefrostEnabled: Boolean? = null,
    val maxDefrostEnabled: Boolean? = null,
    val driverSeatHeatingLevel: Int? = null,
    val passengerSeatHeatingLevel: Int? = null,
    val updatedAtEpochMillis: Long = 0L
)
