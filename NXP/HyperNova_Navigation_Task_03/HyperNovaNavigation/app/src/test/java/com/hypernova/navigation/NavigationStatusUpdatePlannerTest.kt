package com.hypernova.navigation

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.domain.model.DestinationSource
import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.NavigationSessionState
import com.hypernova.navigation.domain.model.NavigationSessionStatus
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.ResolvedDestination
import com.hypernova.navigation.domain.model.RouteAlternative
import com.hypernova.navigation.domain.model.RoutePlan
import com.hypernova.navigation.domain.model.VehiclePosition
import com.hypernova.navigation.service.NavigationStatusUpdatePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationStatusUpdatePlannerTest {
    @Test
    fun initialActiveSnapshotContainsGeometryAndAuthoritativeProgress() {
        val planner = NavigationStatusUpdatePlanner(1_000L)

        val emission = planner.update(activeState(), 5_000L, 10_000L, force = true)

        assertEquals(NavigationContract.STATE_ACTIVE, emission.routeSnapshot?.navigationState)
        assertEquals(3, emission.routeSnapshot?.routePreview?.routePoints?.size)
        assertTrue(emission.routeSnapshot?.routeId?.isNotBlank() == true)
        assertEquals(1L, emission.routeSnapshot?.routeVersion)
        assertNotNull(emission.progressSnapshot?.currentPosition)
        assertEquals(45.0f, emission.progressSnapshot?.currentPosition?.bearingDegrees)
        assertEquals(500L, emission.progressSnapshot?.remainingDistanceMeters)
    }

    @Test
    fun positionTicksDoNotResendGeometryAndAreThrottled() {
        val planner = NavigationStatusUpdatePlanner(1_000L)
        planner.update(activeState(), 0L, 1_000L, force = true)

        val early = planner.update(activeState(progress = 0.6), 500L, 1_500L)
        val due = planner.update(activeState(progress = 0.7), 1_000L, 2_000L)

        assertNull(early.routeSnapshot)
        assertNull(early.progressSnapshot)
        assertNull(due.routeSnapshot)
        assertNotNull(due.progressSnapshot)
        assertEquals(2L, due.progressSnapshot?.sequenceNumber)
    }

    @Test
    fun callbackRegistrationCanForceImmediateFullSnapshot() {
        val planner = NavigationStatusUpdatePlanner(1_000L)
        planner.update(activeState(), 0L, 1_000L, force = true)

        val forced = planner.update(activeState(progress = 0.55), 100L, 1_100L, force = true)

        assertNotNull(forced.routeSnapshot)
        assertNotNull(forced.progressSnapshot)
    }

    @Test
    fun rerouteChangesVersionAndRouteIdentity() {
        val planner = NavigationStatusUpdatePlanner(1_000L)
        val first = planner.update(activeState(), 0L, 1_000L, force = true)
        val rerouted = planner.update(activeState(routeOffset = 0.02), 100L, 1_100L)

        assertNotEquals(first.routeSnapshot?.routeId, rerouted.routeSnapshot?.routeId)
        assertEquals(2L, rerouted.routeSnapshot?.routeVersion)
        assertEquals(2L, rerouted.progressSnapshot?.routeVersion)
    }

    @Test
    fun cancellationClearsRouteGeometryAndPosition() {
        val planner = NavigationStatusUpdatePlanner(1_000L)
        planner.update(activeState(), 0L, 1_000L, force = true)

        val cancelled = planner.update(NavigationSessionState(), 100L, 1_100L)

        assertEquals("", cancelled.routeSnapshot?.routeId)
        assertTrue(cancelled.routeSnapshot?.routePreview?.routePoints?.isEmpty() == true)
        assertEquals(NavigationContract.STATE_IDLE, cancelled.routeSnapshot?.navigationState)
        assertNull(cancelled.progressSnapshot?.currentPosition)
        assertEquals(-1L, cancelled.progressSnapshot?.remainingDistanceMeters)
    }

    @Test
    fun removeScenarioHasNoAdditionalEmissionWithoutIncomingState() {
        val planner = NavigationStatusUpdatePlanner(1_000L)
        val initial = planner.update(activeState(), 0L, 1_000L, force = true)

        assertNotNull(initial.progressSnapshot)
        // The planner is passive: unregistering a Binder observer requires no poll/timer.
        assertFalse(initial.routeSnapshot?.routePreview?.routePoints?.isEmpty() ?: true)
    }

    private fun activeState(
        progress: Double = 0.5,
        routeOffset: Double = 0.0
    ): NavigationSessionState {
        val points = listOf(
            GeoPoint(30.00, 31.00),
            GeoPoint(30.05 + routeOffset, 31.05),
            GeoPoint(30.10, 31.10)
        )
        return NavigationSessionState(
            status = NavigationSessionStatus.ACTIVE,
            destination = destination(),
            routePlan =
                RoutePlan(
                    alternatives =
                        listOf(
                            RouteAlternative(
                                points = points,
                                distanceMeters = 1_000.0,
                                durationSeconds = 300.0,
                                steps = emptyList()
                            )
                        )
                ),
            vehiclePosition =
                VehiclePosition(
                    point = points[1],
                    bearingDegrees = 45.0,
                    speedKph = 36.0,
                    traveledMeters = progress * 1_000.0,
                    remainingDistanceMeters = (1.0 - progress) * 1_000.0,
                    progressFraction = progress,
                    routeSegmentIndex = 1,
                    arrived = false
                )
        )
    }

    private fun destination(): ResolvedDestination =
        ResolvedDestination(
            id = "valeo",
            place =
                Place(
                    displayName = "Valeo, Smart Village",
                    latitude = 30.10,
                    longitude = 31.10,
                    primaryName = "Valeo",
                    formattedAddress = "Smart Village"
                ),
            source = DestinationSource.SEARCH,
            distanceMeters = null
        )
}
