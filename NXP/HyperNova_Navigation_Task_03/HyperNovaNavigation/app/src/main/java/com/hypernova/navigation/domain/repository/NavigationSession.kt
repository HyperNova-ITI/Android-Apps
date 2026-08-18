package com.hypernova.navigation.domain.repository

import com.hypernova.navigation.domain.model.NavigationSessionState
import com.hypernova.navigation.domain.model.NavigationSessionStatus
import com.hypernova.navigation.domain.model.ResolvedDestination
import com.hypernova.navigation.domain.model.RoutePlan
import com.hypernova.navigation.domain.model.VehiclePosition
import java.util.concurrent.CopyOnWriteArraySet

class NavigationSession {
    private val listeners =
        CopyOnWriteArraySet<(NavigationSessionState) -> Unit>()

    @Volatile
    private var state = NavigationSessionState()

    fun current(): NavigationSessionState = state

    fun addListener(
        listener: (NavigationSessionState) -> Unit
    ) {
        listeners += listener
    }

    fun removeListener(
        listener: (NavigationSessionState) -> Unit
    ) {
        listeners -= listener
    }

    @Synchronized
    fun beginCalculation(destination: ResolvedDestination) {
        update(
            NavigationSessionState(
                status = NavigationSessionStatus.CALCULATING,
                destination = destination
            )
        )
    }

    @Synchronized
    fun showRoutePreview(
        destination: ResolvedDestination,
        routePlan: RoutePlan
    ) {
        update(
            NavigationSessionState(
                status = NavigationSessionStatus.ROUTE_PREVIEW,
                destination = destination,
                routePlan = routePlan
            )
        )
    }

    @Synchronized
    fun activate(
        destination: ResolvedDestination? =
            state.destination,
        routePlan: RoutePlan? = state.routePlan
    ): Boolean {
        if (destination == null || routePlan == null) {
            return false
        }

        update(
            NavigationSessionState(
                status = NavigationSessionStatus.ACTIVE,
                destination = destination,
                routePlan = routePlan
            )
        )
        return state.status == NavigationSessionStatus.ACTIVE
    }

    @Synchronized
    fun updateVehiclePosition(position: VehiclePosition): Boolean {
        val current = state
        if (current.status != NavigationSessionStatus.ACTIVE) {
            return false
        }

        update(current.copy(vehiclePosition = position))
        return true
    }

    @Synchronized
    fun arrive(position: VehiclePosition): Boolean {
        val current = state
        if (
            current.status != NavigationSessionStatus.ACTIVE ||
            !position.arrived
        ) {
            return false
        }

        update(
            current.copy(
                status = NavigationSessionStatus.ARRIVED,
                vehiclePosition = position
            )
        )
        return state.status == NavigationSessionStatus.ARRIVED
    }

    @Synchronized
    fun selectRoute(routePlan: RoutePlan): Boolean {
        val current = state
        val destination = current.destination ?: return false
        if (current.routePlan == null) return false

        update(
            current.copy(
                destination = destination,
                routePlan = routePlan
            )
        )
        return true
    }

    @Synchronized
    fun fail(
        destination: ResolvedDestination?,
        message: String?
    ) {
        update(
            NavigationSessionState(
                status = NavigationSessionStatus.ERROR,
                destination = destination,
                message = message
            )
        )
    }

    @Synchronized
    fun restore(
        destination: ResolvedDestination,
        routePlan: RoutePlan,
        active: Boolean
    ) {
        update(
            NavigationSessionState(
                status =
                    if (active) {
                        NavigationSessionStatus.ACTIVE
                    } else {
                        NavigationSessionStatus.ROUTE_PREVIEW
                    },
                destination = destination,
                routePlan = routePlan
            )
        )
    }

    @Synchronized
    fun cancel(): Boolean {
        val wasNavigating =
            state.status != NavigationSessionStatus.IDLE
        if (wasNavigating) {
            update(NavigationSessionState())
        }
        return wasNavigating
    }

    private fun update(newState: NavigationSessionState) {
        state = newState
        listeners.forEach { it(newState) }
    }
}
