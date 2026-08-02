package com.hypernova.navigation

import com.hypernova.navigation.domain.model.DestinationSource
import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.NavigationSessionStatus
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.ResolvedDestination
import com.hypernova.navigation.domain.model.RouteAlternative
import com.hypernova.navigation.domain.model.RoutePlan
import com.hypernova.navigation.domain.model.VehiclePosition
import com.hypernova.navigation.domain.repository.NavigationSession
import com.hypernova.navigation.service.RouteConfirmationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSessionTest {
    @Test
    fun routeIsConfirmableOnlyAfterAuthoritativeStateIsActive() {
        val session = NavigationSession()
        val destination = destination()
        val route = route()

        session.beginCalculation(destination)
        assertFalse(
            RouteConfirmationPolicy.canConfirm(
                session.current(),
                destination.id
            )
        )

        session.showRoutePreview(destination, route)
        assertFalse(
            RouteConfirmationPolicy.canConfirm(
                session.current(),
                destination.id
            )
        )

        assertTrue(session.activate())
        assertTrue(
            RouteConfirmationPolicy.canConfirm(
                session.current(),
                destination.id
            )
        )
    }

    @Test
    fun cancellationWhenActive_transitionsToIdle() {
        val session = NavigationSession()
        val destination = destination()
        session.showRoutePreview(destination, route())
        session.activate()

        assertTrue(session.cancel())
        assertTrue(
            session.current().status ==
                NavigationSessionStatus.IDLE
        )
    }

    @Test
    fun cancellationWhenAlreadyIdle_isIdempotent() {
        val session = NavigationSession()

        assertFalse(session.cancel())
        assertTrue(
            session.current().status ==
                NavigationSessionStatus.IDLE
        )
    }

    @Test
    fun arrival_transitionsActiveSessionToAuthoritativeArrivedState() {
        val session = NavigationSession()
        val destination = destination()
        session.showRoutePreview(destination, route())
        session.activate()

        val finalPoint = route().selected.points.last()
        val arrived =
            VehiclePosition(
                point = finalPoint,
                bearingDegrees = 45.0,
                speedKph = 0.0,
                traveledMeters = 2_000.0,
                remainingDistanceMeters = 0.0,
                progressFraction = 1.0,
                routeSegmentIndex = 0,
                arrived = true
            )

        assertTrue(session.arrive(arrived))
        assertEquals(
            NavigationSessionStatus.ARRIVED,
            session.current().status
        )
        assertEquals(arrived, session.current().vehiclePosition)
        assertTrue(session.cancel())
        assertEquals(
            NavigationSessionStatus.IDLE,
            session.current().status
        )
    }

    private fun destination(): ResolvedDestination =
        ResolvedDestination(
            id = "nav-route",
            place =
                Place(
                    displayName = "Destination",
                    latitude = 30.2,
                    longitude = 31.2
                ),
            source = DestinationSource.SEARCH,
            distanceMeters = 1000L
        )

    private fun route(): RoutePlan =
        RoutePlan(
            alternatives =
                listOf(
                    RouteAlternative(
                        points =
                            listOf(
                                GeoPoint(30.0, 31.0),
                                GeoPoint(30.2, 31.2)
                            ),
                        distanceMeters = 2000.0,
                        durationSeconds = 300.0,
                        steps = emptyList()
                    )
                )
        )
}
