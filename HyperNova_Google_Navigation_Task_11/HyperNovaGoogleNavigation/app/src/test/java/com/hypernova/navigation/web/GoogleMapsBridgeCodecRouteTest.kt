package com.hypernova.navigation.web

import com.hypernova.contracts.navigation.NavigationContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the HOME-widget "cropped route".
 *
 * parseRoute used to stop reading once MAX_ROUTE_PREVIEW_POINTS had been collected, keeping the
 * first 128 points of a route rather than a summary of all of it. A point-count assertion alone
 * passes against that bug, so these tests pin the DESTINATION and the SPAN.
 */
class GoogleMapsBridgeCodecRouteTest {

    /** A long route: Smart Village -> Borg El Arab, ~1.1 degrees of latitude, 3000 points. */
    private fun longRoutePayload(count: Int = 3000): String {
        val points = JSONArray()
        for (i in 0 until count) {
            val t = i.toDouble() / (count - 1)
            points.put(
                JSONObject()
                    .put("latitude", 30.07112 + t * 1.1289)
                    .put("longitude", 31.02075 - t * 1.1021),
            )
        }
        return JSONObject()
            .put("points", points)
            .put("etaSeconds", 7260L)
            .put("distanceMeters", 191_500L)
            .toString()
    }

    @Test
    fun `respects the preview point budget`() {
        val route = GoogleMapsBridgeCodec.parseRoute(longRoutePayload())
        assertTrue(route.points.size <= NavigationContract.MAX_ROUTE_PREVIEW_POINTS)
    }

    @Test
    fun `keeps the real destination rather than a prefix`() {
        val route = GoogleMapsBridgeCodec.parseRoute(longRoutePayload())
        val last = route.points.last()

        // The truncating version ended near 30.119, 30.972 -- still beside the origin.
        assertEquals(31.2000, last.latitude, 0.01)
        assertEquals(29.9187, last.longitude, 0.01)
    }

    @Test
    fun `spans the whole route`() {
        val route = GoogleMapsBridgeCodec.parseRoute(longRoutePayload())
        val span = route.points.maxOf { it.latitude } - route.points.minOf { it.latitude }

        // Truncation gave ~0.048 degrees of a 1.129-degree route.
        assertTrue("route spanned only $span degrees", span > 1.0)
    }

    @Test
    fun `keeps short routes intact and preserves distance`() {
        val route = GoogleMapsBridgeCodec.parseRoute(longRoutePayload(count = 10))
        assertEquals(10, route.points.size)
        assertEquals(191_500L, route.distanceMeters)
        assertEquals(7_260L, route.etaSeconds)
    }
}
