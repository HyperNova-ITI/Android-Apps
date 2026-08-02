package com.hypernova.navigation.domain.simulation

import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.RouteAlternative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimulationEngineTest {
    @Test
    fun initialPosition_startsAtExactRouteOrigin() {
        val engine = engine()
        val initial = engine.initialPosition()

        assertEquals(ROUTE_POINTS.first(), initial?.point)
        assertEquals(0.0, initial?.traveledMeters ?: -1.0, TOLERANCE)
        assertFalse(initial?.arrived ?: true)
    }

    @Test
    fun advance_movesBetweenPolylineVerticesWithoutJumping() {
        val engine = engine(speedFactor = 1.0)
        val position = engine.advance(1.0)

        assertTrue(position != null)
        assertTrue(position!!.point.longitude > ROUTE_POINTS.first().longitude)
        assertTrue(position.point.longitude < ROUTE_POINTS.last().longitude)
        assertTrue(position.progressFraction in 0.0..1.0)
        assertTrue(position.remainingDistanceMeters > 0.0)
    }

    @Test
    fun advanceAtArrival_emitsExactDestinationAndZeroSpeed() {
        val engine = engine(speedFactor = 100.0)
        val position = engine.advance(10.0)

        assertEquals(ROUTE_POINTS.last(), position?.point)
        assertTrue(position?.arrived == true)
        assertEquals(0.0, position?.speedKph ?: -1.0, TOLERANCE)
        assertEquals(0.0, position?.remainingDistanceMeters ?: -1.0, TOLERANCE)
        assertEquals(1.0, position?.progressFraction ?: -1.0, TOLERANCE)
    }

    private fun engine(speedFactor: Double = 8.0): RouteSimulationEngine {
        val geometryDistance =
            RouteSimulationMath.cumulativeDistances(ROUTE_POINTS).last()
        return RouteSimulationEngine(
            route =
                RouteAlternative(
                    points = ROUTE_POINTS,
                    distanceMeters = geometryDistance,
                    durationSeconds = 100.0,
                    steps = emptyList()
                ),
            config =
                RouteSimulationConfig(
                    speedFactor = speedFactor,
                    arrivalSlowdownDistanceMeters = 0.0
                )
        )
    }

    private companion object {
        const val TOLERANCE = 0.000_001
        val ROUTE_POINTS =
            listOf(
                GeoPoint(30.0, 31.0),
                GeoPoint(30.0, 31.01)
            )
    }
}
