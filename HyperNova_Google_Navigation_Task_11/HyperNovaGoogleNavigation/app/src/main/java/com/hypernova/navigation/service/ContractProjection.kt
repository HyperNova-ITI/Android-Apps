package com.hypernova.navigation.service

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationCurrentPosition
import com.hypernova.contracts.navigation.NavigationDestination
import com.hypernova.contracts.navigation.NavigationProgressSnapshot
import com.hypernova.contracts.navigation.NavigationRoutePoint
import com.hypernova.contracts.navigation.NavigationRoutePreview
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
        val points = state.routePoints.map { NavigationRoutePoint(it.latitude, it.longitude) }
        val current =
            state.vehiclePosition?.point?.let {
                NavigationRoutePoint(it.latitude, it.longitude)
            }
        return NavigationRoutePreview(points, current)
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
}
