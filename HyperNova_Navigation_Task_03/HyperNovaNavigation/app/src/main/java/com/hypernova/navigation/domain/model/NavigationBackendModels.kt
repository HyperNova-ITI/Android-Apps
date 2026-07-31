package com.hypernova.navigation.domain.model

enum class DestinationSource {
    SEARCH,
    SAVED_HOME,
    SAVED_WORK,
    SAVED_FAVORITE
}

data class ResolvedDestination(
    val id: String,
    val place: Place,
    val source: DestinationSource,
    val distanceMeters: Long?
)

sealed interface DestinationResolution {
    data class Found(
        val destination: ResolvedDestination
    ) : DestinationResolution

    data object Expired : DestinationResolution

    data object Unknown : DestinationResolution
}

enum class NavigationSessionStatus {
    IDLE,
    CALCULATING,
    ROUTE_PREVIEW,
    ACTIVE,
    ERROR
}

data class NavigationSessionState(
    val status: NavigationSessionStatus =
        NavigationSessionStatus.IDLE,
    val destination: ResolvedDestination? = null,
    val routePlan: RoutePlan? = null,
    val message: String? = null
)
