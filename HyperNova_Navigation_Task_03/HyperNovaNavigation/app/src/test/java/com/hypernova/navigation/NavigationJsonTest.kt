package com.hypernova.navigation

import com.hypernova.navigation.domain.model.NavigationJson
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.PlaceProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationJsonTest {

    @Test
    fun enrichedOverpassPlace_survivesStateSnapshotRoundTrip() {
        val place =
            Place(
                displayName = "Test Cafe, Smart Village",
                latitude = 30.07,
                longitude = 31.02,
                category = "Food",
                type = "cafe",
                provider = PlaceProvider.OVERPASS,
                providerId = "overpass:node:42",
                osmType = "node",
                osmId = 42L,
                primaryName = "Test Cafe",
                formattedAddress = "Smart Village",
                brand = "Test Brand",
                operator = "Test Operator",
                subcategory = "cafe",
                phone = "+20 100 000 0000",
                website = "https://example.com",
                openingHours = "Mo-Fr 09:00-17:00",
                straightLineDistanceMeters = 125.0
            )

        val restored =
            requireNotNull(
                NavigationJson.placeFromJson(
                    NavigationJson.placeToJson(place)
                )
            )

        assertEquals(place, restored)
        assertEquals("overpass:node:42", restored.id)
    }
}
