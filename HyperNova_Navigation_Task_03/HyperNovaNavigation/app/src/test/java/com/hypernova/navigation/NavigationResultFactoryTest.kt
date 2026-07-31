package com.hypernova.navigation

import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.domain.model.DestinationSource
import com.hypernova.navigation.domain.model.NavigationSessionState
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.ResolvedDestination
import com.hypernova.navigation.service.NavigationResultFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationResultFactoryTest {
    private val factory =
        NavigationResultFactory {
            NavigationSessionState()
        }

    @Test
    fun zeroSearchResults_returnsContractNoResultsFailure() {
        val result =
            factory.searchResult(
                "request",
                emptyList()
            )

        assertEquals(
            HyperNovaContract.STATUS_REJECTED,
            result.status
        )
        assertEquals(
            NavigationContract.ERROR_NO_RESULTS,
            result.errorCode
        )
    }

    @Test
    fun oneSearchResult_returnsIssuedDestination() {
        val destination =
            ResolvedDestination(
                id = "nav-opaque",
                place =
                    Place(
                        displayName = "Place, Cairo",
                        latitude = 30.0,
                        longitude = 31.0,
                        category = "generic"
                    ),
                source = DestinationSource.SEARCH,
                distanceMeters = 500L
            )

        val result =
            factory.searchResult(
                "request",
                listOf(destination)
            )

        assertEquals(
            HyperNovaContract.STATUS_CONFIRMED,
            result.status
        )
        assertEquals(
            "nav-opaque",
            result.destinations.single().id
        )
        assertEquals(
            500L,
            result.destinations.single().distanceMeters
        )
    }

    @Test
    fun timeoutResult_usesContractStatusAndError() {
        val result =
            factory.timeout(
                requestId = "request",
                operation =
                    NavigationContract.OP_SEARCH_DESTINATIONS,
                message = "Timed out."
            )

        assertEquals(
            HyperNovaContract.STATUS_TIMEOUT,
            result.status
        )
        assertEquals(
            HyperNovaContract.ERROR_TIMEOUT,
            result.errorCode
        )
    }
}
