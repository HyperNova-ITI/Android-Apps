package com.hypernova.climate.model

/** Single- or dual-zone cabin (README §6). */
enum class ClimateZoneMode { SINGLE, DUAL }

/** Freshness of the confirmed state coming from the vehicle. */
enum class ClimateAvailability { UNAVAILABLE, AVAILABLE, STALE }

/** Header/system mode (README §14, §27). */
enum class ClimateMode {
    STARTING, OFF, AUTO, MANUAL, COOLING, HEATING, DEFROST, MAX_DEFROST,
    PENDING, UNAVAILABLE, ERROR
}

/** Backend health for the health card (README §20). */
enum class ClimateHealth {
    NORMAL, DEGRADED, COMMUNICATION_LOST, SENSOR_FAILURE,
    ACTUATOR_FAILURE, SERVICE_UNAVAILABLE
}

/** Airflow direction (README §23). */
enum class AirflowMode { FACE, FEET, FACE_AND_FEET, WINDSHIELD, FEET_AND_DEFROST }

/** Cabin air quality (README §15.2). */
enum class AirQualityState { GOOD, MODERATE, POOR, UNAVAILABLE }

/**
 * A/C control mode for the cockpit demo: off, cooling, or heating.
 * On a real vehicle A/C is cooling-only and heat comes from the temperature
 * setpoint; HEAT here is a UI/intent mode for the demo.
 */
enum class AcMode { OFF, COOL, HEAT }
