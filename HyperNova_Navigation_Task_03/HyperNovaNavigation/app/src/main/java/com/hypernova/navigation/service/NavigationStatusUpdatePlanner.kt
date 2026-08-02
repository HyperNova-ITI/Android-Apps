package com.hypernova.navigation.service

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationProgressSnapshot
import com.hypernova.contracts.navigation.NavigationRouteSnapshot
import com.hypernova.navigation.domain.model.NavigationSessionState

internal data class NavigationStatusEmission(
    val routeSnapshot: NavigationRouteSnapshot?,
    val progressSnapshot: NavigationProgressSnapshot?
)

/** Synchronized route-version and progress-throttling state machine. */
internal class NavigationStatusUpdatePlanner(
    private val minimumProgressIntervalMillis: Long =
        NavigationContract.MIN_PROGRESS_UPDATE_INTERVAL_MILLIS
) {
    private var initialized = false
    private var routeId = ""
    private var routeVersion = 0L
    private var lastContractState = NavigationContract.STATE_IDLE
    private var lastPositionAvailable = false
    private var lastProgressElapsedMillis = Long.MIN_VALUE
    private var sequenceNumber = 0L

    @Synchronized
    fun update(
        state: NavigationSessionState,
        elapsedRealtimeMillis: Long,
        wallClockMillis: Long,
        force: Boolean = false
    ): NavigationStatusEmission {
        val newRouteId = NavigationStatusSnapshotFactory.routeId(state)
        val newContractState = NavigationStatusSnapshotFactory.contractState(state)
        val routeIdentityChanged = initialized && newRouteId != routeId
        val stateChanged = initialized && newContractState != lastContractState
        val positionAvailable = state.vehiclePosition != null
        val positionAvailabilityChanged =
            initialized && positionAvailable != lastPositionAvailable

        if (!initialized) {
            routeId = newRouteId
            routeVersion = if (newRouteId.isBlank()) 0L else 1L
        } else if (routeIdentityChanged) {
            routeId = newRouteId
            routeVersion += 1L
        }

        val emitRoute = force || !initialized || routeIdentityChanged || stateChanged
        val intervalElapsed =
            lastProgressElapsedMillis == Long.MIN_VALUE ||
                elapsedRealtimeMillis - lastProgressElapsedMillis >=
                minimumProgressIntervalMillis
        val emitProgress =
            force ||
                !initialized ||
                routeIdentityChanged ||
                stateChanged ||
                positionAvailabilityChanged ||
                intervalElapsed

        val routeSnapshot =
            if (emitRoute) {
                NavigationStatusSnapshotFactory.routeSnapshot(
                    state,
                    routeId,
                    routeVersion
                )
            } else {
                null
            }
        val progressSnapshot =
            if (emitProgress) {
                sequenceNumber += 1L
                lastProgressElapsedMillis = elapsedRealtimeMillis
                NavigationStatusSnapshotFactory.progressSnapshot(
                    state,
                    routeId,
                    routeVersion,
                    sequenceNumber,
                    wallClockMillis
                )
            } else {
                null
            }

        initialized = true
        lastContractState = newContractState
        lastPositionAvailable = positionAvailable
        return NavigationStatusEmission(routeSnapshot, progressSnapshot)
    }
}
