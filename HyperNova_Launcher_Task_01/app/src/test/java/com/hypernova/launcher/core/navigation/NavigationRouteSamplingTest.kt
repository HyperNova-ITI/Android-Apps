package com.hypernova.launcher.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRouteSamplingTest {

    private fun route(count: Int): List<NavigationPreviewPoint> =
        (0 until count).map {
            NavigationPreviewPoint(
                latitude = 30.0 + it * 0.001,
                longitude = 31.0 + it * 0.001,
            )
        }

    @Test
    fun `keeps a short route untouched`() {
        val points = route(10)
        assertEquals(points, points.sampleForPreview(96))
    }

    @Test
    fun `never exceeds the budget`() {
        assertTrue(route(5000).sampleForPreview(96).size <= 96)
    }

    /**
     * The regression this file exists for. `take(limit)` also satisfied the budget, so a size-only
     * assertion passes against the broken implementation. Pinning the LAST point is what actually
     * distinguishes sampling the whole route from framing its opening stretch.
     */
    @Test
    fun `keeps the destination, not just the start`() {
        val points = route(5000)
        val sampled = points.sampleForPreview(96)

        assertEquals(points.first(), sampled.first())
        assertEquals(points.last(), sampled.last())
    }

    @Test
    fun `spans the whole route rather than a prefix`() {
        val points = route(1000)
        val sampled = points.sampleForPreview(96)

        // A truncating implementation would end around index 95 of 999.
        val midpoint = sampled[sampled.size / 2]
        assertTrue(
            "sampled midpoint should sit near the route midpoint",
            midpoint.latitude > points[400].latitude && midpoint.latitude < points[600].latitude,
        )
    }

    @Test
    fun `is monotonic and free of duplicates`() {
        val sampled = route(3000).sampleForPreview(96)
        val latitudes = sampled.map { it.latitude }
        assertEquals(latitudes.sorted(), latitudes)
        assertEquals(latitudes.distinct().size, latitudes.size)
    }

    @Test
    fun `handles degenerate budgets without throwing`() {
        val points = route(500)
        assertEquals(points, points.sampleForPreview(1))
        assertEquals(points, points.sampleForPreview(0))
        assertEquals(2, points.sampleForPreview(2).size)
    }
}
