package com.hypernova.navigation.service

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationCurrentPosition
import com.hypernova.contracts.navigation.NavigationDestination
import com.hypernova.contracts.navigation.NavigationProgressSnapshot
import com.hypernova.contracts.navigation.NavigationRoutePreview
import com.hypernova.contracts.navigation.NavigationRouteSnapshot
import com.hypernova.navigation.domain.model.DestinationSource
import com.hypernova.navigation.domain.model.NavigationSessionState
import com.hypernova.navigation.domain.model.NavigationSessionStatus
import com.hypernova.navigation.domain.model.ResolvedDestination
import kotlin.math.roundToLong

/** Maps the shared Navigation session into the additive observer contract. */
internal object NavigationStatusSnapshotFactory {
    fun routeId(state: NavigationSessionState): String {
        val destination = state.destination ?: return ""
        val routePlan = state.routePlan ?: return ""
        if (routePlan.alternatives.isEmpty()) return ""

        val selected = routePlan.selected
        var hash = FNV_OFFSET_BASIS
        hash = fnv(hash, destination.id.hashCode().toLong())
        hash = fnv(hash, routePlan.selectedIndex.toLong())
        hash = fnv(hash, selected.distanceMeters.toBits())
        hash = fnv(hash, selected.durationSeconds.toBits())
        selected.points.forEach { point ->
            hash = fnv(hash, point.latitude.toBits())
            hash = fnv(hash, point.longitude.toBits())
        }
        return destination.id + ":" + java.lang.Long.toUnsignedString(hash, 16)
    }

    fun routeSnapshot(
        state: NavigationSessionState,
        routeId: String,
        routeVersion: Long
    ): NavigationRouteSnapshot {
        val route =
            state.routePlan
                ?.takeIf { it.alternatives.isNotEmpty() }
                ?.selected
        val mappedPreview = NavigationRoutePreviewMapper.fromState(state)

        return NavigationRouteSnapshot(
            routeId,
            routeVersion,
            state.toContractState(),
            state.destination?.toContract(),
            route
                ?.durationSeconds
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.roundToLong()
                ?: UNAVAILABLE,
            route
                ?.distanceMeters
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.roundToLong()
                ?: UNAVAILABLE,
            NavigationRoutePreview(
                mappedPreview.routePoints,
                null
            )
        )
    }

    fun progressSnapshot(
        state: NavigationSessionState,
        routeId: String,
        routeVersion: Long,
        sequenceNumber: Long,
        timestampMillis: Long
    ): NavigationProgressSnapshot {
        val vehiclePosition = state.vehiclePosition
        val point = vehiclePosition?.point
        val currentPosition =
            if (
                point != null &&
                point.latitude.isFinite() &&
                point.longitude.isFinite() &&
                point.latitude in -90.0..90.0 &&
                point.longitude in -180.0..180.0
            ) {
                NavigationCurrentPosition(
                    point.latitude,
                    point.longitude,
                    normalizeBearing(vehiclePosition.bearingDegrees),
                    vehiclePosition.speedKph
                        .takeIf { it.isFinite() && it >= 0.0 }
                        ?.div(KILOMETERS_PER_HOUR_PER_METER_PER_SECOND)
                        ?.toFloat()
                        ?: Float.NaN,
                    timestampMillis
                )
            } else {
                null
            }
        val remainingDistance =
            vehiclePosition
                ?.remainingDistanceMeters
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.roundToLong()
                ?: UNAVAILABLE

        return NavigationProgressSnapshot(
            routeId,
            routeVersion,
            state.toContractState(),
            sequenceNumber,
            currentPosition,
            remainingDistance
        )
    }

    fun contractState(state: NavigationSessionState): Int =
        state.toContractState()

    private fun NavigationSessionState.toContractState(): Int =
        when (status) {
            NavigationSessionStatus.IDLE,
            NavigationSessionStatus.ROUTE_PREVIEW ->
                NavigationContract.STATE_IDLE
            NavigationSessionStatus.CALCULATING ->
                NavigationContract.STATE_CALCULATING
            NavigationSessionStatus.ACTIVE ->
                NavigationContract.STATE_ACTIVE
            NavigationSessionStatus.ARRIVED ->
                NavigationContract.STATE_ARRIVED
            NavigationSessionStatus.ERROR ->
                NavigationContract.STATE_ERROR
        }

    private fun ResolvedDestination.toContract(): NavigationDestination =
        NavigationDestination(
            id,
            source.toContractSource(),
            place.name,
            place.address.ifBlank { place.displayName },
            place.categoryDescription
                .ifBlank { place.category.ifBlank { place.type } },
            distanceMeters ?: UNAVAILABLE
        )

    private fun DestinationSource.toContractSource(): Int =
        when (this) {
            DestinationSource.SEARCH -> NavigationContract.SOURCE_SEARCH
            DestinationSource.SAVED_HOME -> NavigationContract.SOURCE_SAVED_HOME
            DestinationSource.SAVED_WORK -> NavigationContract.SOURCE_SAVED_WORK
            DestinationSource.SAVED_FAVORITE ->
                NavigationContract.SOURCE_SAVED_FAVORITE
        }

    private fun normalizeBearing(value: Double): Float =
        if (value.isFinite()) {
            (((value % FULL_CIRCLE_DEGREES) + FULL_CIRCLE_DEGREES) %
                FULL_CIRCLE_DEGREES).toFloat()
        } else {
            Float.NaN
        }

    private fun fnv(current: Long, value: Long): Long =
        (current xor value) * FNV_PRIME

    private const val UNAVAILABLE = -1L
    private const val KILOMETERS_PER_HOUR_PER_METER_PER_SECOND = 3.6
    private const val FULL_CIRCLE_DEGREES = 360.0
    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
}
