package com.hypernova.navigation

import com.hypernova.navigation.domain.model.NearbyCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyCategoryTest {

    @Test
    fun categories_useApprovedOsmTagSelectors() {
        assertEquals(
            listOf(
                "PARKING",
                "FUEL",
                "FOOD",
                "HOSPITAL",
                "SHOPPING"
            ),
            NearbyCategory.entries.map { it.name }
        )
        assertEquals(
            "[\"amenity\"=\"parking\"]",
            NearbyCategory.PARKING.osmSelector
        )
        assertEquals(
            "[\"amenity\"=\"fuel\"]",
            NearbyCategory.FUEL.osmSelector
        )
        assertEquals(
            "[\"amenity\"~\"^(restaurant|fast_food|cafe|food_court)$\"]",
            NearbyCategory.FOOD.osmSelector
        )
        assertEquals(
            "[\"amenity\"~\"^(hospital|clinic|doctors)$\"]",
            NearbyCategory.HOSPITAL.osmSelector
        )
        assertEquals(
            "[\"shop\"]",
            NearbyCategory.SHOPPING.osmSelector
        )
    }
}
