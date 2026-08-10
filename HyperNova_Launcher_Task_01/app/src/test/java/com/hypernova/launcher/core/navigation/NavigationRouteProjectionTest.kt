package com.hypernova.launcher.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRouteProjectionTest {
    @Test
    fun `vertical route is centered and finite`() {
        val projection = project(
            listOf(
                NavigationPreviewPoint(30.0, 31.0),
                NavigationPreviewPoint(30.1, 31.0),
                NavigationPreviewPoint(30.2, 31.0),
            )
        )

        assertNotNull(projection)
        val points = projection!!.routePoints
        assertEquals(points.first().x, points.last().x, 0.001f)
        assertTrue(points.all(::insideViewport))
        assertTrue(points.first().y > points.last().y)
    }

    @Test
    fun `horizontal route is centered and finite`() {
        val projection = project(
            listOf(
                NavigationPreviewPoint(30.0, 31.0),
                NavigationPreviewPoint(30.0, 31.1),
                NavigationPreviewPoint(30.0, 31.2),
            )
        )

        assertNotNull(projection)
        val points = projection!!.routePoints
        assertEquals(points.first().y, points.last().y, 0.001f)
        assertTrue(points.all(::insideViewport))
        assertTrue(points.first().x < points.last().x)
    }

    @Test
    fun `very short route remains safe and centered`() {
        val projection = project(
            listOf(
                NavigationPreviewPoint(30.0000000, 31.0000000),
                NavigationPreviewPoint(30.0000001, 31.0000001),
            )
        )

        assertNotNull(projection)
        assertTrue(projection!!.routePoints.all(::insideViewport))
    }

    @Test
    fun `authoritative current position uses the same projection`() {
        val current = NavigationPreviewPoint(30.1, 31.1)
        val projection = NavigationRouteProjection.project(
            routePoints = listOf(
                NavigationPreviewPoint(30.0, 31.0),
                NavigationPreviewPoint(30.2, 31.2),
            ),
            currentPosition = current,
            left = 10f,
            top = 10f,
            right = 190f,
            bottom = 90f,
        )

        assertNotNull(projection?.currentPosition)
        assertTrue(insideViewport(projection!!.currentPosition!!))
    }

    private fun project(points: List<NavigationPreviewPoint>) =
        NavigationRouteProjection.project(
            routePoints = points,
            currentPosition = null,
            left = 10f,
            top = 10f,
            right = 190f,
            bottom = 90f,
        )

    private fun insideViewport(point: NavigationCanvasPoint): Boolean =
        point.x.isFinite() &&
            point.y.isFinite() &&
            point.x in 10f..190f &&
            point.y in 10f..90f
}
