package com.hypernova.navigation

import com.hypernova.navigation.domain.model.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceModelTest {

    @Test
    fun nameAndAddress_areDerivedFromProviderDisplayName() {
        val place =
            Place(
                displayName =
                    "Cairo University, Giza, Egypt",
                latitude = 30.027,
                longitude = 31.209
            )

        assertEquals("Cairo University", place.name)
        assertEquals("Giza, Egypt", place.address)
    }
}
