package com.hypernova.launcher.core.navigation

import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationProgressSnapshot
import com.hypernova.contracts.navigation.NavigationResult
import com.hypernova.contracts.navigation.NavigationRoutePreviewResult
import com.hypernova.contracts.navigation.NavigationRouteSnapshot
import com.hypernova.launcher.core.state.AppConnectionState

enum class NavigationRuntimeState {
    UNAVAILABLE,
    IDLE,
    CALCULATING,
    ACTIVE,
    ARRIVED,
    ERROR,
}

/** Launcher-owned snapshot of Navigation's read-only current-state result. */
data class NavigationStatusSnapshot(
    val connectionState: AppConnectionState,
    val runtimeState: NavigationRuntimeState = NavigationRuntimeState.UNAVAILABLE,
    val routeId: String = "",
    val routeVersion: Long = 0L,
    val destinationTitle: String? = null,
    val destinationSubtitle: String? = null,
    val etaSeconds: Long? = null,
    val distanceMeters: Long? = null,
    val routePoints: List<NavigationPreviewPoint> = emptyList(),
    val currentPosition: NavigationPreviewPoint? = null,
    val currentBearingDegrees: Float? = null,
    val currentSpeedMetersPerSecond: Float? = null,
    val currentPositionTimestampMillis: Long? = null,
    val progressSequenceNumber: Long = 0L,
    val remainingDistanceMeters: Long? = null,
    val errorMessage: String? = null,
) {
    val positionAvailable: Boolean
        get() = currentPosition != null

    val hasRoutePreview: Boolean
        get() =
            runtimeState == NavigationRuntimeState.IDLE &&
                routeId.isNotBlank() &&
                !destinationTitle.isNullOrBlank()
}

/** Launcher-owned coordinate; contract parcelables never reach the View layer. */
data class NavigationPreviewPoint(
    val latitude: Double,
    val longitude: Double,
)

/** Pure mapping kept separate so all contract states are testable. */
object NavigationStatusMapper {
    fun runtimeState(contractState: Int): NavigationRuntimeState = when (contractState) {
        NavigationContract.STATE_IDLE -> NavigationRuntimeState.IDLE
        NavigationContract.STATE_CALCULATING -> NavigationRuntimeState.CALCULATING
        NavigationContract.STATE_ACTIVE -> NavigationRuntimeState.ACTIVE
        NavigationContract.STATE_ARRIVED -> NavigationRuntimeState.ARRIVED
        NavigationContract.STATE_ERROR -> NavigationRuntimeState.ERROR
        else -> NavigationRuntimeState.UNAVAILABLE
    }

    fun fromResult(result: NavigationResult): NavigationStatusSnapshot {
        val runtimeState = runtimeState(result.navigationState)
        val destination = result.selectedDestination
        val isFailure =
            result.status != HyperNovaContract.STATUS_ACCEPTED &&
                result.status != HyperNovaContract.STATUS_CONFIRMED
        return NavigationStatusSnapshot(
            connectionState =
                if (isFailure) {
                    AppConnectionState.ERROR
                } else {
                    AppConnectionState.READY
                },
            runtimeState = runtimeState,
            // NavigationResult predates the additive versioned route observer and therefore has
            // no dedicated route-id field. For the read-only current-state operation, the
            // selected destination token is a stable, truthful route identity fallback. This
            // keeps a preview visible if Android drops an observer callback or the client binds
            // after route preparation.
            routeId =
                destination?.id
                    ?.takeIf {
                        result.operation == NavigationContract.OP_GET_CURRENT_STATE &&
                            it.isNotBlank()
                    }
                    .orEmpty(),
            destinationTitle = destination?.title?.takeIf(String::isNotBlank),
            destinationSubtitle = destination?.subtitle?.takeIf(String::isNotBlank),
            etaSeconds = result.etaSeconds.takeIf { it >= 0L },
            distanceMeters = result.distanceMeters.takeIf { it >= 0L },
            errorMessage = result.message.takeIf {
                it.isNotBlank() &&
                    (isFailure || runtimeState == NavigationRuntimeState.ERROR)
            },
        )
    }

    fun withRoutePreview(
        snapshot: NavigationStatusSnapshot,
        result: NavigationRoutePreviewResult,
    ): NavigationStatusSnapshot {
        val previewAllowed =
            (snapshot.runtimeState == NavigationRuntimeState.IDLE &&
                snapshot.routeId.isNotBlank()) ||
                snapshot.runtimeState == NavigationRuntimeState.CALCULATING ||
                snapshot.runtimeState == NavigationRuntimeState.ACTIVE ||
                snapshot.runtimeState == NavigationRuntimeState.ARRIVED
        val matchesState = runtimeState(result.navigationState) == snapshot.runtimeState
        if (
            result.status != HyperNovaContract.STATUS_CONFIRMED ||
            !previewAllowed ||
            !matchesState
        ) {
            return snapshot.copy(routePoints = emptyList(), currentPosition = null)
        }

        val mappedRoutePoints =
            result.routePreview.routePoints
                .asSequence()
                .mapNotNull { point ->
                    NavigationPreviewPoint(
                        latitude = point.latitude,
                        longitude = point.longitude,
                    ).takeIf(::isValidPreviewPoint)
                }
                .take(NavigationContract.MAX_ROUTE_PREVIEW_POINTS)
                .toList()
        val routePoints = mappedRoutePoints.takeIf { it.size >= 2 }.orEmpty()
        val currentPosition =
            if (routePoints.isNotEmpty()) {
                result.routePreview.currentPosition
                    ?.let { point ->
                        NavigationPreviewPoint(point.latitude, point.longitude)
                    }
                    ?.takeIf(::isValidPreviewPoint)
            } else {
                null
            }

        return snapshot.copy(
            routePoints = routePoints,
            currentPosition = currentPosition,
        )
    }

    /** Map one versioned route snapshot while rejecting stale route versions. */
    fun withRouteSnapshot(
        previous: NavigationStatusSnapshot,
        result: NavigationRouteSnapshot,
    ): NavigationStatusSnapshot {
        if (result.routeVersion < previous.routeVersion) return previous
        if (
            result.routeVersion == previous.routeVersion &&
            previous.routeId.isNotBlank() &&
            result.routeId != previous.routeId
        ) {
            return previous
        }

        val runtimeState = runtimeState(result.navigationState)
        val previewAllowed = runtimeState in PREVIEW_STATES
        val routePoints =
            if (previewAllowed) {
                validatedRoutePoints(result.routePreview.routePoints)
            } else {
                emptyList()
            }
        val sameRoute =
            result.routeVersion == previous.routeVersion &&
                result.routeId == previous.routeId &&
                result.routeId.isNotBlank()
        val destination = result.selectedDestination

        return NavigationStatusSnapshot(
            connectionState = AppConnectionState.READY,
            runtimeState = runtimeState,
            routeId = result.routeId,
            routeVersion = result.routeVersion,
            destinationTitle = destination?.title?.takeIf(String::isNotBlank),
            destinationSubtitle = destination?.subtitle?.takeIf(String::isNotBlank),
            etaSeconds = result.etaSeconds.takeIf { it >= 0L },
            distanceMeters = result.distanceMeters.takeIf { it >= 0L },
            routePoints = routePoints,
            currentPosition = previous.currentPosition.takeIf { sameRoute && routePoints.isNotEmpty() },
            currentBearingDegrees = previous.currentBearingDegrees.takeIf { sameRoute },
            currentSpeedMetersPerSecond = previous.currentSpeedMetersPerSecond.takeIf { sameRoute },
            currentPositionTimestampMillis =
                previous.currentPositionTimestampMillis.takeIf { sameRoute },
            progressSequenceNumber = previous.progressSequenceNumber,
            remainingDistanceMeters = previous.remainingDistanceMeters.takeIf { sameRoute },
        )
    }

    /** Merge a lightweight progress event only when its route/version is current. */
    fun withProgressSnapshot(
        previous: NavigationStatusSnapshot,
        result: NavigationProgressSnapshot,
    ): NavigationStatusSnapshot {
        if (result.sequenceNumber <= previous.progressSequenceNumber) return previous
        if (
            result.routeVersion != previous.routeVersion ||
            result.routeId != previous.routeId
        ) {
            return previous
        }

        val resultRuntimeState = runtimeState(result.navigationState)
        if (resultRuntimeState != previous.runtimeState) return previous

        val currentPosition =
            result.currentPosition
                ?.let { position ->
                    NavigationPreviewPoint(position.latitude, position.longitude)
                }
                ?.takeIf(::isValidPreviewPoint)
                ?.takeIf { previous.routePoints.isNotEmpty() }
        val bearing =
            result.currentPosition
                ?.bearingDegrees
                ?.takeIf(Float::isFinite)
                ?.let(::normalizeBearing)
        val speed =
            result.currentPosition
                ?.speedMetersPerSecond
                ?.takeIf { it.isFinite() && it >= 0f }
        val timestamp =
            result.currentPosition
                ?.timestampMillis
                ?.takeIf { it > 0L }

        return previous.copy(
            currentPosition = currentPosition,
            currentBearingDegrees = bearing.takeIf { currentPosition != null },
            currentSpeedMetersPerSecond = speed.takeIf { currentPosition != null },
            currentPositionTimestampMillis = timestamp.takeIf { currentPosition != null },
            progressSequenceNumber = result.sequenceNumber,
            remainingDistanceMeters = result.remainingDistanceMeters.takeIf { it >= 0L },
        )
    }

    private fun validatedRoutePoints(
        points: List<com.hypernova.contracts.navigation.NavigationRoutePoint>,
    ): List<NavigationPreviewPoint> {
        val mapped =
            points.asSequence()
                .mapNotNull { point ->
                    NavigationPreviewPoint(point.latitude, point.longitude)
                        .takeIf(::isValidPreviewPoint)
                }
                .take(NavigationContract.MAX_ROUTE_PREVIEW_POINTS)
                .toList()
        return mapped.takeIf { it.size >= 2 }.orEmpty()
    }

    private fun normalizeBearing(value: Float): Float =
        ((value % FULL_CIRCLE_DEGREES) + FULL_CIRCLE_DEGREES) %
            FULL_CIRCLE_DEGREES

    private fun isValidPreviewPoint(point: NavigationPreviewPoint): Boolean =
        point.latitude.isFinite() &&
            point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 &&
            point.longitude in -180.0..180.0

    private val PREVIEW_STATES = setOf(
        // A prepared route is intentionally public as IDLE until START is confirmed.
        NavigationRuntimeState.IDLE,
        NavigationRuntimeState.CALCULATING,
        NavigationRuntimeState.ACTIVE,
        NavigationRuntimeState.ARRIVED,
    )
    private const val FULL_CIRCLE_DEGREES = 360f
}
