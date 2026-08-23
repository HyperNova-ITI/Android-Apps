package com.hypernova.navigation.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMapsPageTest {
    @Test
    fun pageUsesWebApisAndFrozenTrustedOriginWithoutGmsRuntime() {
        val page = GoogleMapsPage.render("AIza_test-key")

        assertTrue(page.contains("maps.googleapis.com/maps/api/js"))
        assertTrue(page.contains("PlaceClass.searchByText"))
        assertTrue(page.contains("RouteClass.computeRoutes"))
        assertTrue(page.contains("maxResultCount: 4"))
        assertTrue(page.contains("clickableIcons: true"))
        assertTrue(page.contains("destinationPlace.fetchFields"))
        assertTrue(page.contains("userRatingCount"))
        assertTrue(page.contains("Selected Google Maps place details"))
        assertTrue(page.contains("Current location · ITI Smart Village"))
        assertTrue(page.contains("map.addListener('click'"))
        assertTrue(page.contains("event.placeId"))
        assertTrue(page.contains("gmpClickable: true"))
        assertTrue(page.contains("HyperNovaBridge.onDestinationRequested"))
        assertTrue(page.contains("window.hypernovaStartGuidance"))
        assertTrue(page.contains("HyperNovaBridge.onGuidanceProgress"))
        assertTrue(page.contains("fields: ['displayName', 'reviews']"))
        assertTrue(page.contains("place.attributions"))
        assertTrue(page.contains("connect-src data: https://*.googleapis.com"))
        assertTrue(page.contains("mapId: '${GoogleMapsPage.MAP_ID}'"))
        assertTrue(page.contains("colorScheme: 'LIGHT'"))
        assertTrue(GoogleMapsPage.REQUESTED_CUSTOM_MAP_ID.isNotBlank())
        assertTrue(GoogleMapsPage.DOCUMENT_ORIGIN.startsWith("https://"))
        assertTrue(page.contains("v=quarterly"))
        assertFalse(page.contains("libraries=places,routes,marker"))
        assertFalse(page.contains("NavigationApi"))
        assertFalse(page.contains("com.google.android.gms"))
    }

    @Test
    fun mapAndChromeFollowNightMode() {
        val nightPage = GoogleMapsPage.render("AIza_test-key", isNightMode = true)

        assertTrue(nightPage.contains("colorScheme: 'DARK'"))
        assertTrue(nightPage.contains("--nova-page: #07121d"))
    }

    @Test
    fun browserKeyIsUrlEncodedBeforeInsertion() {
        val page = GoogleMapsPage.render("key&with=query")

        assertTrue(page.contains("key%26with%3Dquery"))
        assertFalse(page.contains("key&with=query"))
    }
}
