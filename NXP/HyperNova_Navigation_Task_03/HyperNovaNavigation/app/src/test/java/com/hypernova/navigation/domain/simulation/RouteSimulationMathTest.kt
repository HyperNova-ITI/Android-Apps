package com.hypernova.navigation.domain.simulation

import com.hypernova.navigation.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimulationMathTest {
    @Test
    fun cumulativeDistance_increasesForEachNonZeroSegment() {
        val cumulative =
            RouteSimulationMath.cumulativeDistances(ROUTE)

        assertEquals(3, cumulative.size)
        assertEquals(0.0, cumulative[0], TOLERANCE)
        assertTrue(cumulative[1] > cumulative[0])
        assertTrue(cumulative[2] > cumulative[1])
    }

    @Test
    fun pointAlong_atStart_returnsFirstPoint() {
        val sample = pointAlong(0.0)

        assertEquals(ROUTE.first(), sample?.point)
    }

    @Test
    fun pointAlong_atEnd_returnsLastPoint() {
        val cumulative =
            RouteSimulationMath.cumulativeDistances(ROUTE)
        val sample =
            RouteSimulationMath.pointAlong(
                ROUTE,
                cumulative,
                cumulative.last()
            )

        assertEquals(ROUTE.last(), sample?.point)
    }

    @Test
    fun pointAlong_betweenVertices_interpolatesOnSegment() {
        val cumulative =
            RouteSimulationMath.cumulativeDistances(ROUTE)
        val sample =
            RouteSimulationMath.pointAlong(
                ROUTE,
                cumulative,
                cumulative[1] / 2.0
            ) ?: error("Expected an interpolated route point")

        assertEquals(0.0, sample.point.latitude, TOLERANCE)
        assertEquals(0.005, sample.point.longitude, 0.000_01)
        assertEquals(0, sample.segmentIndex)
    }

    @Test
    fun bearing_dueEast_isApproximatelyNinetyDegrees() {
        val bearing =
            RouteSimulationMath.bearingDegrees(
                GeoPoint(0.0, 0.0),
                GeoPoint(0.0, 1.0)
            )

        assertEquals(90.0, bearing, 0.01)
    }

    @Test
    fun angleDelta_wrapsAcrossNorthByShortestDirection() {
        assertEquals(
            2.0,
            RouteSimulationMath.angleDelta(359.0, 1.0),
            TOLERANCE
        )
        assertEquals(
            -2.0,
            RouteSimulationMath.angleDelta(1.0, 359.0),
            TOLERANCE
        )
    }

    @Test
    fun pointAlong_beyondEnd_clampsToFinalPoint() {
        val cumulative =
            RouteSimulationMath.cumulativeDistances(ROUTE)
        val sample =
            RouteSimulationMath.pointAlong(
                ROUTE,
                cumulative,
                cumulative.last() + 50_000.0
            )

        assertEquals(ROUTE.last(), sample?.point)
    }

    @Test
    fun emptyAndSinglePointRoutes_areSafe() {
        assertTrue(
            RouteSimulationMath.cumulativeDistances(emptyList()).isEmpty()
        )
        assertNull(
            RouteSimulationMath.pointAlong(
                emptyList(),
                DoubleArray(0),
                10.0
            )
        )

        val onlyPoint = GeoPoint(30.0, 31.0)
        val sample =
            RouteSimulationMath.pointAlong(
                listOf(onlyPoint),
                doubleArrayOf(0.0),
                10.0
            )
        assertEquals(onlyPoint, sample?.point)
        assertEquals(0.0, sample?.bearingDegrees ?: -1.0, TOLERANCE)
    }

    private fun pointAlong(distance: Double):
        RouteSimulationMath.PointAlongRoute? =
        RouteSimulationMath.pointAlong(
            ROUTE,
            RouteSimulationMath.cumulativeDistances(ROUTE),
            distance
        )

    private companion object {
        const val TOLERANCE = 0.000_001
        val ROUTE =
            listOf(
                GeoPoint(0.0, 0.0),
                GeoPoint(0.0, 0.01),
                GeoPoint(0.01, 0.01)
            )
    }
}
