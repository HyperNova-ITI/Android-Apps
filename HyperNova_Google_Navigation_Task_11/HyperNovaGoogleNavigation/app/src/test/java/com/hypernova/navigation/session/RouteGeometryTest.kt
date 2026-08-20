package com.hypernova.navigation.session

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {
    @Test
    fun maximumMustBeAtLeastTwo() {
        assertThrows(IllegalArgumentException::class.java) { RouteGeometry.sanitizeAndBound(point(0.0, 0.0).asList(), 0) }
        assertThrows(IllegalArgumentException::class.java) { RouteGeometry.sanitizeAndBound(point(0.0, 0.0).asList(), 1) }
    }

    @Test
    fun nonFiniteCoordinatesAreFilteredOut() {
        val points =
            listOf(
                point(1.0, 2.0),
                point(Double.NaN, 2.0),
                point(1.0, Double.NaN),
                point(Double.POSITIVE_INFINITY, 2.0),
                point(1.0, Double.NEGATIVE_INFINITY),
                point(Double.NaN, Double.NaN),
            )

        val result = RouteGeometry.sanitizeAndBound(points, maximum = 128)

        assertEquals(listOf(point(1.0, 2.0)), result)
    }

    @Test
    fun coordinatesOutsideWorldBoundsAreFilteredOut() {
        val points =
            listOf(
                point(0.0, 0.0),
                point(90.1, 0.0),
                point(-90.1, 0.0),
                point(0.0, 180.1),
                point(0.0, -180.1),
            )

        val result = RouteGeometry.sanitizeAndBound(points, maximum = 128)

        assertEquals(listOf(point(0.0, 0.0)), result)
    }

    @Test
    fun boundaryCoordinatesAreValid() {
        val points = listOf(point(90.0, 180.0), point(-90.0, -180.0))

        assertEquals(points, RouteGeometry.sanitizeAndBound(points, maximum = 128))
    }

    @Test
    fun adjacentDuplicatesAreCollapsed() {
        val a = point(0.0, 0.0)
        val b = point(1.0, 1.0)
        val result = RouteGeometry.sanitizeAndBound(listOf(a, a, a, b, b, a), maximum = 128)

        assertEquals(listOf(a, b, a), result)
    }

    @Test
    fun smallRoutesAreReturnedUnchangedOnceValidated() {
        val points = listOf(point(1.0, 1.0), point(2.0, 2.0), point(3.0, 3.0))

        assertEquals(points, RouteGeometry.sanitizeAndBound(points, maximum = 128))
    }

    @Test
    fun oversizedRoutesAreBoundedToMaximumPointsKeepingEndpoints() {
        val points = List(1_000) { index ->
            val fraction = index / 999.0
            point(
                lat = -89.0 + (178.0 * fraction),
                lon = -179.0 + (358.0 * fraction),
            )
        }

        val result = RouteGeometry.sanitizeAndBound(points, NavigationContract.MAX_ROUTE_PREVIEW_POINTS)

        assertEquals(NavigationContract.MAX_ROUTE_PREVIEW_POINTS, result.size)
        assertEquals(points.first(), result.first())
        assertEquals(points.last(), result.last())
    }

    @Test
    fun boundingToTwoPointsKeepsFirstAndLast() {
        val points = List(20) { point(it.toDouble(), it.toDouble()) }

        val result = RouteGeometry.sanitizeAndBound(points, maximum = 2)

        assertEquals(listOf(points.first(), points.last()), result)
    }

    @Test
    fun boundingCanCollapseDuplicatedSamplesBelowMaximum() {
        val a = point(0.0, 0.0)
        val b = point(1.0, 0.0)
        val route = List(50) { if (it % 2 == 0) a else b }

        val result = RouteGeometry.sanitizeAndBound(route, maximum = 3)

        assertTrue(result.size in 1..3)
    }

    private fun point(lat: Double, lon: Double): GeoPoint = GeoPoint(latitude = lat, longitude = lon)

    private fun GeoPoint.asList(): List<GeoPoint> = listOf(this)
}
