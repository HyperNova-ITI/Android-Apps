package com.hypernova.launcher.ui

import com.hypernova.launcher.core.navigation.NavigationPreviewPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherStaticMapRequestTest {
    @Test
    fun `idle request uses fixed ITI location and never exposes key in logs`() {
        val request = LauncherStaticMapRequest.build("test key", emptyList())

        assertTrue(request.startsWith("https://maps.googleapis.com/maps/api/staticmap?"))
        assertTrue(request.contains("center=30.07112%2C31.02075"))
        assertTrue(request.contains("zoom=15"))
        assertTrue(request.contains("map_id=20d0a8fe56e67ae4e0d3323d"))
        assertTrue(request.endsWith("key=test%20key"))
        assertFalse(request.contains("path="))
    }

    @Test
    fun `route request carries encoded path and endpoint markers`() {
        val points =
            listOf(
                NavigationPreviewPoint(38.5, -120.2),
                NavigationPreviewPoint(40.7, -120.95),
                NavigationPreviewPoint(43.252, -126.453),
            )

        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", LauncherStaticMapRequest.encodePolyline(points))

        val request = LauncherStaticMapRequest.build("key", points)
        assertTrue(request.contains("path="))
        assertTrue(request.contains("enc%3A_p%7EiF%7Eps%7CU_ulLnnqC_mqNvxq%60%40"))
        assertTrue(request.split("markers=").size - 1 == 2)
        assertFalse(request.contains("center="))
    }
}
