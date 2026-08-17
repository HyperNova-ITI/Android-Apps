package com.hypernova.navigation

import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.repository.DestinationSearchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationSearchPolicyTest {
    @Test
    fun blankSearchQuery_isRejectedByNormalization() {
        assertNull(
            DestinationSearchPolicy.normalizedQuery("   ")
        )
        assertNull(
            DestinationSearchPolicy.normalizedQuery(null)
        )
    }

    @Test
    fun zeroSearchResults_remainEmpty() {
        assertEquals(
            emptyList<Place>(),
            DestinationSearchPolicy.limitResults(emptyList())
        )
    }

    @Test
    fun oneSearchResult_isPreserved() {
        val only = place("Only")

        assertEquals(
            listOf(only),
            DestinationSearchPolicy.limitResults(
                listOf(only)
            )
        )
    }

    @Test
    fun moreThanFourResults_areLimitedWithOrderPreserved() {
        val providerOrder =
            (1..6).map { place("Place $it") }

        assertEquals(
            providerOrder.take(4),
            DestinationSearchPolicy.limitResults(
                providerOrder
            )
        )
    }

    private fun place(name: String): Place =
        Place(
            displayName = name,
            latitude = 30.0,
            longitude = 31.0
        )
}
