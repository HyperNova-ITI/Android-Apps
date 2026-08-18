package com.hypernova.navigation.domain.simulation

import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.RouteAlternative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimulationControllerTest {
    private val controller =
        RouteSimulationController(
            RouteSimulationConfig(
                speedFactor = 100.0,
                arrivalSlowdownDistanceMeters = 0.0
            )
        )

    @Test
    fun startAndCancel_manageSingleSimulationLifecycle() {
        val route = route(31.0, 31.01)

        val initial = controller.start(route)
        assertTrue(controller.isRunning)
        assertEquals(route.points.first(), initial?.point)

        controller.cancel()
        assertFalse(controller.isRunning)
        assertNull(controller.currentPosition)
    }

    @Test
    fun arrivalStopsControllerAtExactDestination() {
        val route = route(31.0, 31.01)
        controller.start(route)

        val arrived = controller.tick(10.0)

        assertTrue(arrived?.arrived == true)
        assertEquals(route.points.last(), arrived?.point)
        assertFalse(controller.isRunning)
    }

    @Test
    fun routeReplacementRestartsAtNewRouteOrigin() {
        val first = route(31.0, 31.01)
        val replacement = route(32.0, 32.02)
        controller.start(first)
        controller.tick(0.1)

        val replacementInitial = controller.start(replacement)

        assertTrue(controller.isRunning)
        assertEquals(replacement.points.first(), replacementInitial?.point)
        assertEquals(0.0, replacementInitial?.traveledMeters ?: -1.0, 0.0)
    }

    private fun route(
        startLongitude: Double,
        endLongitude: Double
    ): RouteAlternative {
        val points =
            listOf(
                GeoPoint(30.0, startLongitude),
                GeoPoint(30.0, endLongitude)
            )
        return RouteAlternative(
            points = points,
            distanceMeters =
                RouteSimulationMath
                    .cumulativeDistances(points)
                    .last(),
            durationSeconds = 100.0,
            steps = emptyList()
        )
    }
}
