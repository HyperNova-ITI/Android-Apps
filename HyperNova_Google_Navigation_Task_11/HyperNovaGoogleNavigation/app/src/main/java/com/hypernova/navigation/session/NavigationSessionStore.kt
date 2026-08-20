package com.hypernova.navigation.session

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.model.FailureKind
import com.hypernova.navigation.model.GeoPoint
import com.hypernova.navigation.model.GoogleDestinationRecord
import com.hypernova.navigation.model.NavigationInitializationState
import com.hypernova.navigation.model.NavigationPhase
import com.hypernova.navigation.model.NavigationSessionState
import com.hypernova.navigation.model.RouteData
import com.hypernova.navigation.model.VehiclePosition
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NavigationSessionStore(initial: NavigationSessionState) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<NavigationSessionState> = mutableState.asStateFlow()

    @Synchronized
    fun initialization(
        value: NavigationInitializationState,
        message: String,
        errorCode: String = "",
    ) {
        mutableState.value =
            mutableState.value.copy(
                initialization = value,
                statusMessage = message,
                errorCode = errorCode,
                routeVersion = mutableState.value.routeVersion + 1,
                progressSequence = mutableState.value.progressSequence + 1,
            )
    }

    @Synchronized
    fun searching(active: Boolean) {
        val current = mutableState.value
        if (current.phase !in setOf(NavigationPhase.IDLE, NavigationPhase.SEARCHING)) return
        mutableState.value =
            current.copy(
                phase = if (active) NavigationPhase.SEARCHING else NavigationPhase.IDLE,
                statusMessage = if (active) "Searching Google Places…" else "Ready",
            )
    }

    @Synchronized
    fun calculating(
        token: String,
        destination: GoogleDestinationRecord,
        source: Int,
        simulated: Boolean = false,
    ) {
        val current = mutableState.value
        mutableState.value =
            current.copy(
                phase = NavigationPhase.CALCULATING,
                statusMessage = "Calculating Google route…",
                errorCode = "",
                selectedToken = token,
                selectedSource = source,
                selectedDestination = destination,
                routeId = UUID.randomUUID().toString(),
                routeVersion = current.routeVersion + 1,
                progressSequence = current.progressSequence + 1,
                routePoints = emptyList(),
                etaSeconds = -1L,
                distanceMeters = -1L,
                vehiclePosition = null,
                simulated = simulated,
            )
    }

    @Synchronized
    fun routeReady(route: RouteData, simulated: Boolean = false): NavigationSessionState {
        val current = mutableState.value
        val bounded =
            RouteGeometry.sanitizeAndBound(
                route.points,
                NavigationContract.MAX_ROUTE_PREVIEW_POINTS,
            )
        mutableState.value =
            current.copy(
                phase = NavigationPhase.PREVIEW_READY,
                statusMessage = "Route preview ready",
                errorCode = "",
                routeVersion = current.routeVersion + 1,
                progressSequence = current.progressSequence + 1,
                routePoints = bounded,
                etaSeconds = route.etaSeconds.coerceAtLeast(-1L),
                distanceMeters = route.distanceMeters.coerceAtLeast(-1L),
                simulated = simulated,
            )
        return mutableState.value
    }

    @Synchronized
    fun guidanceStarted() {
        val current = mutableState.value
        if (current.phase != NavigationPhase.PREVIEW_READY) return
        mutableState.value =
            current.copy(
                phase = NavigationPhase.GUIDING,
                statusMessage = if (current.simulated) "Simulated guidance" else "Guidance active",
                routeVersion = current.routeVersion + 1,
                progressSequence = current.progressSequence + 1,
            )
    }

    @Synchronized
    fun rerouting() {
        val current = mutableState.value
        if (current.phase != NavigationPhase.GUIDING) return
        mutableState.value =
            current.copy(
                phase = NavigationPhase.REROUTING,
                statusMessage = "Rerouting…",
                progressSequence = current.progressSequence + 1,
            )
    }

    @Synchronized
    fun routeChanged(route: RouteData) {
        val current = mutableState.value
        if (current.routeId.isBlank()) return
        mutableState.value =
            current.copy(
                phase =
                    if (current.phase in setOf(NavigationPhase.GUIDING, NavigationPhase.REROUTING)) {
                        NavigationPhase.GUIDING
                    } else {
                        NavigationPhase.PREVIEW_READY
                    },
                statusMessage = if (current.simulated) "Simulated route updated" else "Route updated",
                routeVersion = current.routeVersion + 1,
                progressSequence = current.progressSequence + 1,
                routePoints =
                    RouteGeometry.sanitizeAndBound(
                        route.points,
                        NavigationContract.MAX_ROUTE_PREVIEW_POINTS,
                    ),
                etaSeconds = route.etaSeconds.coerceAtLeast(-1L),
                distanceMeters = route.distanceMeters.coerceAtLeast(-1L),
            )
    }

    @Synchronized
    fun progress(etaSeconds: Long, distanceMeters: Long, position: VehiclePosition? = null) {
        val current = mutableState.value
        if (current.routeId.isBlank()) return
        mutableState.value =
            current.copy(
                progressSequence = current.progressSequence + 1,
                etaSeconds = etaSeconds.coerceAtLeast(-1L),
                distanceMeters = distanceMeters.coerceAtLeast(-1L),
                vehiclePosition = position ?: current.vehiclePosition,
            )
    }

    @Synchronized
    fun position(position: VehiclePosition) {
        val current = mutableState.value
        if (current.routeId.isBlank()) return
        mutableState.value =
            current.copy(
                progressSequence = current.progressSequence + 1,
                vehiclePosition = position,
            )
    }

    @Synchronized
    fun arrived() {
        val current = mutableState.value
        if (
            current.routeId.isBlank() ||
            current.phase !in setOf(NavigationPhase.GUIDING, NavigationPhase.REROUTING)
        ) {
            return
        }
        mutableState.value =
            current.copy(
                phase = NavigationPhase.ARRIVED,
                statusMessage = if (current.simulated) "Simulated arrival" else "Arrived",
                routeVersion = current.routeVersion + 1,
                progressSequence = current.progressSequence + 1,
                etaSeconds = 0L,
                distanceMeters = 0L,
            )
    }

    @Synchronized
    fun routeFailure(kind: FailureKind, message: String) {
        val current = mutableState.value
        mutableState.value =
            current.copy(
                phase = NavigationPhase.ERROR,
                statusMessage = message,
                errorCode = kind.name,
                routeVersion = current.routeVersion + 1,
                progressSequence = current.progressSequence + 1,
                routePoints = emptyList(),
                etaSeconds = -1L,
                distanceMeters = -1L,
            )
    }

    @Synchronized
    fun cancelled() {
        val current = mutableState.value
        mutableState.value =
            current.copy(
                phase = NavigationPhase.IDLE,
                statusMessage = "Navigation idle",
                errorCode = "",
                selectedToken = null,
                selectedSource = 0,
                selectedDestination = null,
                routeId = "",
                routeVersion = current.routeVersion + 1,
                progressSequence = current.progressSequence + 1,
                routePoints = emptyList(),
                etaSeconds = -1L,
                distanceMeters = -1L,
                vehiclePosition = null,
                simulated = false,
            )
    }

    fun current(): NavigationSessionState = mutableState.value
}
