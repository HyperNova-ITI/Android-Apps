package com.hypernova.navigation.domain.simulation

data class RouteSimulationConfig(
    val tickMillis: Long = DEFAULT_TICK_MILLIS,
    val speedFactor: Double = DEFAULT_SPEED_FACTOR,
    val bearingSmoothing: Double = DEFAULT_BEARING_SMOOTHING,
    val fallbackSpeedMetersPerSecond: Double =
        DEFAULT_FALLBACK_SPEED_METERS_PER_SECOND,
    val arrivalSlowdownDistanceMeters: Double =
        DEFAULT_ARRIVAL_SLOWDOWN_DISTANCE_METERS,
    val minimumArrivalSpeedFraction: Double =
        DEFAULT_MINIMUM_ARRIVAL_SPEED_FRACTION
) {
    companion object {
        const val DEFAULT_TICK_MILLIS = 100L
        const val DEFAULT_SPEED_FACTOR = 8.0
        const val DEFAULT_BEARING_SMOOTHING = 0.18
        const val DEFAULT_FALLBACK_SPEED_METERS_PER_SECOND = 13.9
        const val DEFAULT_ARRIVAL_SLOWDOWN_DISTANCE_METERS = 120.0
        const val DEFAULT_MINIMUM_ARRIVAL_SPEED_FRACTION = 0.25
    }
}
