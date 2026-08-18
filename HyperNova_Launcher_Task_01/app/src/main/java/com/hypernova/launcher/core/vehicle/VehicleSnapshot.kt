package com.hypernova.launcher.core.vehicle

/**
 * Read-only TC397 telemetry as the launcher needs it.
 *
 * Null means the value was never received or the gateway declared it unavailable; the status bar
 * shows a placeholder rather than inventing a number. [fresh] is the gateway's own judgement that
 * the reading is recent enough to display.
 */
data class VehicleSnapshot(
    val connected: Boolean = false,
    val fresh: Boolean = false,
    val cabinTemperatureC: Int? = null,
    val humidityPercent: Int? = null,
    val fuelPercent: Int? = null,
)
