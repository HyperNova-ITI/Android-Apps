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
import com.hypernova.navigation.service.NavigationRoutePreviewMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRoutePreviewMapperTest {
    @Test
    fun emptyRoute_returnsEmptyPreview() {
        val preview = NavigationRoutePreviewMapper.fromState(activeState(emptyList()))

        assertTrue(preview.routePoints.isEmpty())
        assertNull(preview.currentPosition)
    }

    @Test
    fun twoPointRoute_remainsIntact() {
        val points = listOf(GeoPoint(30.0, 31.0), GeoPoint(30.1, 31.2))

        val simplified = NavigationRoutePreviewMapper.simplifyRoutePoints(points)

        assertEquals(points, simplified)
    }

    @Test
    fun routeUnderMaximum_remainsIntact() {
        val points = routePoints(64)

        assertEquals(
            points,
            NavigationRoutePreviewMapper.simplifyRoutePoints(points)
        )
    }

    @Test
    fun largeRoute_isBoundedAndPreservesEndpoints() {
        val points = routePoints(2_000)

        val simplified = NavigationRoutePreviewMapper.simplifyRoutePoints(points)

        assertEquals(NavigationContract.MAX_ROUTE_PREVIEW_POINTS, simplified.size)
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }

    @Test
    fun invalidCoordinates_areRemovedBeforeSampling() {
        val points = listOf(
            GeoPoint(Double.NaN, 31.0),
            GeoPoint(91.0, 31.0),
            GeoPoint(30.0, 31.0),
            GeoPoint(30.1, 31.1),
            GeoPoint(30.2, 181.0)
        )

        assertEquals(
            listOf(GeoPoint(30.0, 31.0), GeoPoint(30.1, 31.1)),
            NavigationRoutePreviewMapper.simplifyRoutePoints(points)
        )
    }

    @Test
    fun idleState_neverReturnsStaleGeometry() {
        val state = NavigationSessionState(
            status = NavigationSessionStatus.IDLE,
            routePlan = routePlan(routePoints(4))
        )

        assertTrue(NavigationRoutePreviewMapper.fromState(state).routePoints.isEmpty())
    }

    @Test
    fun activeState_includesAuthoritativeVehiclePosition() {
        val vehiclePoint = GeoPoint(30.05, 31.06)
        val state = activeState(routePoints(4)).copy(
            vehiclePosition = VehiclePosition(
                point = vehiclePoint,
                bearingDegrees = 45.0,
                speedKph = 30.0,
                traveledMeters = 500.0,
                remainingDistanceMeters = 500.0,
                progressFraction = 0.5,
                routeSegmentIndex = 1,
                arrived = false
            )
        )

        val currentPosition =
            NavigationRoutePreviewMapper.fromState(state).currentPosition

        assertEquals(vehiclePoint.latitude, currentPosition?.latitude ?: 0.0, 0.0)
        assertEquals(vehiclePoint.longitude, currentPosition?.longitude ?: 0.0, 0.0)
    }

    private fun activeState(points: List<GeoPoint>): NavigationSessionState =
        NavigationSessionState(
            status = NavigationSessionStatus.ACTIVE,
            destination = destination(),
            routePlan = routePlan(points)
        )

    private fun routePlan(points: List<GeoPoint>): RoutePlan =
        RoutePlan(
            alternatives = listOf(
                RouteAlternative(
                    points = points,
                    distanceMeters = 1_000.0,
                    durationSeconds = 300.0,
                    steps = emptyList()
                )
            )
        )

    private fun destination(): ResolvedDestination =
        ResolvedDestination(
            id = "destination",
            place = Place("Destination, Cairo", 30.2, 31.2),
            source = DestinationSource.SEARCH,
            distanceMeters = null
        )

    private fun routePoints(count: Int): List<GeoPoint> =
        List(count) { index ->
            GeoPoint(
                latitude = 29.5 + index * 0.0001,
                longitude = 30.5 + index * 0.00015
            )
        }
}
