package com.hypernova.navigation.model

enum class NavigationInitializationState {
    INITIALIZING,
    CONFIGURATION_REQUIRED,
    TERMS_REQUIRED,
    READY_IDLE,
    GOOGLE_SERVICES_UNAVAILABLE,
    LOCATION_UNAVAILABLE,
    ERROR,
}

enum class NavigationPhase {
    IDLE,
    SEARCHING,
    CALCULATING,
    PREVIEW_READY,
    GUIDING,
    REROUTING,
    ARRIVED,
    ERROR,
}

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

data class VehiclePosition(
    val point: GeoPoint,
    val bearingDegrees: Float,
    val speedMetersPerSecond: Float,
    val timestampMillis: Long,
)

data class RouteData(
    val points: List<GeoPoint>,
    val etaSeconds: Long,
    val distanceMeters: Long,
)

data class NavigationSessionState(
    val initialization: NavigationInitializationState,
    val phase: NavigationPhase = NavigationPhase.IDLE,
    val statusMessage: String,
    val errorCode: String = "",
    val selectedToken: String? = null,
    val selectedSource: Int = 0,
    val selectedDestination: GoogleDestinationRecord? = null,
    val routeId: String = "",
    val routeVersion: Long = 0L,
    val progressSequence: Long = 0L,
    val routePoints: List<GeoPoint> = emptyList(),
    val etaSeconds: Long = -1L,
    val distanceMeters: Long = -1L,
    val vehiclePosition: VehiclePosition? = null,
    val simulated: Boolean = false,
)

sealed interface RoutePreparationResult {
    data class Ready(val state: NavigationSessionState) : RoutePreparationResult
    data class Failed(val kind: FailureKind, val message: String) : RoutePreparationResult
}

enum class FailureKind {
    CONFIGURATION,
    TERMS,
    LOCATION,
    NETWORK,
    NO_ROUTE,
    CANCELLED,
    AUTHORIZATION,
    INTERNAL,
}
