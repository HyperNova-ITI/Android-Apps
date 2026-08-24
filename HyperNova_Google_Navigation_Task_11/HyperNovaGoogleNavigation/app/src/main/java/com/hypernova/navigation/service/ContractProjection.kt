package com.hypernova.navigation.service

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationCurrentPosition
import com.hypernova.contracts.navigation.NavigationDestination
import com.hypernova.contracts.navigation.NavigationProgressSnapshot
import com.hypernova.contracts.navigation.NavigationRoutePreview
import com.hypernova.contracts.navigation.NavigationRoutePoint
import com.hypernova.contracts.navigation.NavigationRouteSnapshot
import com.hypernova.navigation.model.NavigationInitializationState
import com.hypernova.navigation.model.NavigationPhase
import com.hypernova.navigation.model.NavigationSessionState
import com.hypernova.navigation.persistence.DestinationTokenEntry

object ContractProjection {
    fun state(state: NavigationSessionState): Int =
        when (state.phase) {
            NavigationPhase.CALCULATING -> NavigationContract.STATE_CALCULATING
            NavigationPhase.GUIDING,
            NavigationPhase.REROUTING,
            -> NavigationContract.STATE_ACTIVE
            NavigationPhase.ARRIVED -> NavigationContract.STATE_ARRIVED
            NavigationPhase.ERROR -> NavigationContract.STATE_ERROR
            NavigationPhase.IDLE,
            NavigationPhase.SEARCHING,
            NavigationPhase.PREVIEW_READY,
            ->
                when (state.initialization) {
                    NavigationInitializationState.CONFIGURATION_REQUIRED,
                    NavigationInitializationState.GOOGLE_SERVICES_UNAVAILABLE,
                    NavigationInitializationState.ERROR,
                    -> NavigationContract.STATE_ERROR
                    else -> NavigationContract.STATE_IDLE
                }
        }

    fun destination(entry: DestinationTokenEntry): NavigationDestination =
        NavigationDestination(
            entry.token,
            entry.source,
            entry.record.title,
            entry.record.subtitle,
            entry.record.category,
            -1L,
        )

    fun selectedDestination(state: NavigationSessionState): NavigationDestination? {
        val token = state.selectedToken ?: return null
        val record = state.selectedDestination ?: return null
        return NavigationDestination(
            token,
            state.selectedSource.takeIf { it != 0 } ?: NavigationContract.SOURCE_SEARCH,
            record.title,
            record.subtitle,
            record.category,
            -1L,
        )
    }

    fun preview(state: NavigationSessionState): NavigationRoutePreview {
        if (
            state.phase !in
                setOf(
                    NavigationPhase.PREVIEW_READY,
                    NavigationPhase.GUIDING,
                    NavigationPhase.REROUTING,
                    NavigationPhase.ARRIVED,
                )
        ) {
            return NavigationRoutePreview.empty()
        }

        /*
         * This bounded projection is rendered by Launcher on an abstract Canvas,
         * never over MapLibre or another map provider. The Launcher labels the
         * route as Google Maps data and does not persist the coordinates.
         */
        val points = simplifyRoutePoints(state.routePoints)
        if (points.size < 2) return NavigationRoutePreview.empty()
        val current =
            state.vehiclePosition
                ?.point
                ?.takeIf(::isValidPoint)
                ?.let { NavigationRoutePoint(it.latitude, it.longitude) }
        return NavigationRoutePreview(
            points.map { NavigationRoutePoint(it.latitude, it.longitude) },
            current,
        )
    }

    fun routeSnapshot(state: NavigationSessionState): NavigationRouteSnapshot =
        NavigationRouteSnapshot(
            state.routeId,
            state.routeVersion,
            state(state),
            selectedDestination(state),
            state.etaSeconds,
            state.distanceMeters,
            preview(state),
        )

    fun progressSnapshot(state: NavigationSessionState): NavigationProgressSnapshot =
        NavigationProgressSnapshot(
            state.routeId,
            state.routeVersion,
            state(state),
            state.progressSequence,
            state.vehiclePosition?.takeIf { position ->
                position.point.latitude.isFinite() &&
                    position.point.longitude.isFinite() &&
                    position.point.latitude in -90.0..90.0 &&
                    position.point.longitude in -180.0..180.0
            }?.let { position ->
                NavigationCurrentPosition(
                    position.point.latitude,
                    position.point.longitude,
                    position.bearingDegrees
                        .takeIf(Float::isFinite)
                        ?.let { ((it % 360f) + 360f) % 360f }
                        ?: Float.NaN,
                    position.speedMetersPerSecond
                        .takeIf { it.isFinite() && it >= 0f }
                        ?: Float.NaN,
                    position.timestampMillis,
                )
            },
            state.distanceMeters,
        )

    private fun simplifyRoutePoints(
        source: List<com.hypernova.navigation.model.GeoPoint>,
    ): List<com.hypernova.navigation.model.GeoPoint> {
        val valid = source.filter(::isValidPoint)
        val maximum = NavigationContract.MAX_ROUTE_PREVIEW_POINTS
        if (valid.size <= maximum) return valid
        val last = valid.lastIndex
        return List(maximum) { outputIndex ->
            valid[(outputIndex.toLong() * last / (maximum - 1L)).toInt()]
        }
    }

    private fun isValidPoint(point: com.hypernova.navigation.model.GeoPoint): Boolean =
        point.latitude.isFinite() &&
            point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 &&
            point.longitude in -180.0..180.0
}
