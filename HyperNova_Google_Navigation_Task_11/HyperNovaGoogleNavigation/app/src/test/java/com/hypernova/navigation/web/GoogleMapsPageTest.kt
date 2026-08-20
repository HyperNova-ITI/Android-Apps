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
        assertTrue(GoogleMapsPage.DOCUMENT_ORIGIN.startsWith("https://"))
        assertFalse(page.contains("NavigationApi"))
        assertFalse(page.contains("com.google.android.gms"))
    }

    @Test
    fun browserKeyIsUrlEncodedBeforeInsertion() {
        val page = GoogleMapsPage.render("key&with=query")

        assertTrue(page.contains("key%26with%3Dquery"))
        assertFalse(page.contains("key&with=query"))
    }
}
