package com.hypernova.launcher.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherGoogleMapsPageTest {
    @Test
    fun idleWidgetIsProductFacingAndOpensNavigation() {
        val page = LauncherGoogleMapsPage.render("AIza_test-key", isNightMode = false)

        assertTrue(page.contains("mapId: '${LauncherGoogleMapsPage.MAP_ID}'"))
        assertTrue(page.contains("colorScheme: 'LIGHT'"))
        assertTrue(page.contains("renderingType: RenderingType.RASTER"))
        assertTrue(page.contains("disableDefaultUI: true"))
        assertTrue(page.contains("gestureHandling: 'none'"))
        assertTrue(page.contains("HyperNovaLauncherBridge.openNavigation()"))
        assertTrue(page.contains("window.hypernovaShowRoute"))
        assertTrue(page.contains("new google.maps.Polyline"))
        assertTrue(!page.contains("searchByText"))
        assertTrue(!page.contains("computeRoutes"))
        assertTrue(!page.contains("libraries=places,routes,marker"))
    }

    @Test
    fun widgetFollowsNightMode() {
        val page = LauncherGoogleMapsPage.render("AIza_test-key", isNightMode = true)

        assertTrue(page.contains("colorScheme: 'DARK'"))
        assertTrue(page.contains("background: #07121d"))
    }
}
