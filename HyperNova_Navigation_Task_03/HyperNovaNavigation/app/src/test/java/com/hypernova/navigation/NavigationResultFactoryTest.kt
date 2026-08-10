package com.hypernova.navigation

import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.domain.model.DestinationSource
import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.NavigationSessionState
import com.hypernova.navigation.domain.model.NavigationSessionStatus
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.ResolvedDestination
import com.hypernova.navigation.domain.model.RouteAlternative
import com.hypernova.navigation.domain.model.RoutePlan
import com.hypernova.navigation.service.NavigationResultFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun arrivedSession_mapsToContractArrivedState() {
        val arrivedFactory =
            NavigationResultFactory {
                NavigationSessionState(
                    status = NavigationSessionStatus.ARRIVED
                )
            }

        assertEquals(
            NavigationContract.STATE_ARRIVED,
            arrivedFactory.currentContractState()
        )
    }

    @Test
    fun destinationSetResult_confirmsPreparedRouteWithoutStartingGuidance() {
        val destination = destination()
        val state =
            NavigationSessionState(
                status = NavigationSessionStatus.ROUTE_PREVIEW,
                destination = destination,
                routePlan = routePlan()
            )

        val result =
            factory.destinationSetResult(
                requestId = "set-destination-request",
                destination = destination,
                state = state
            )

        assertEquals(NavigationContract.OP_SET_DESTINATION, result.operation)
        assertEquals(HyperNovaContract.STATUS_CONFIRMED, result.status)
        assertEquals(NavigationContract.STATE_IDLE, result.navigationState)
        assertEquals("Smart Village", result.selectedDestination?.title)
        assertEquals(1_925L, result.etaSeconds)
        assertEquals(28_410L, result.distanceMeters)
    }

    @Test
    fun currentStateResult_mapsIdleSessionWithoutRouteDetails() {
        val result = factory.currentStateResult("idle-request")

        assertEquals(NavigationContract.OP_GET_CURRENT_STATE, result.operation)
        assertEquals(HyperNovaContract.STATUS_CONFIRMED, result.status)
        assertEquals(NavigationContract.STATE_IDLE, result.navigationState)
        assertNull(result.selectedDestination)
        assertEquals(-1L, result.etaSeconds)
        assertEquals(-1L, result.distanceMeters)
    }

    @Test
    fun currentStateResult_mapsCalculatingDestinationWithoutInventingMetrics() {
        val destination = destination()
        val result = NavigationResultFactory {
            NavigationSessionState(
                status = NavigationSessionStatus.CALCULATING,
                destination = destination
            )
        }.currentStateResult("calculating-request")

        assertEquals(NavigationContract.STATE_CALCULATING, result.navigationState)
        assertEquals(destination.place.name, result.selectedDestination?.title)
        assertEquals(-1L, result.etaSeconds)
        assertEquals(-1L, result.distanceMeters)
    }

    @Test
    fun currentStateResult_mapsActiveDestinationAndPlannedRouteMetrics() {
        val destination = destination()
        val result = NavigationResultFactory {
            NavigationSessionState(
                status = NavigationSessionStatus.ACTIVE,
                destination = destination,
                routePlan = routePlan()
            )
        }.currentStateResult("active-request")

        assertEquals(NavigationContract.STATE_ACTIVE, result.navigationState)
        assertEquals("Smart Village", result.selectedDestination?.title)
        assertEquals("Cairo-Alexandria Desert Road", result.selectedDestination?.subtitle)
        assertEquals(1_925L, result.etaSeconds)
        assertEquals(28_410L, result.distanceMeters)
    }

    @Test
    fun currentRoutePreviewResult_mapsActiveSelectedGeometry() {
        val result = NavigationResultFactory {
            NavigationSessionState(
                status = NavigationSessionStatus.ACTIVE,
                destination = destination(),
                routePlan = routePlan()
            )
        }.currentRoutePreviewResult("active-preview-request")

        assertEquals(HyperNovaContract.STATUS_CONFIRMED, result.status)
        assertEquals(NavigationContract.STATE_ACTIVE, result.navigationState)
        assertEquals(3, result.routePreview.routePoints.size)
        assertEquals(30.0726, result.routePreview.routePoints.last().latitude, 0.0)
    }

    @Test
    fun currentRoutePreviewResult_mapsIdleToEmptyGeometry() {
        val result = factory.currentRoutePreviewResult("idle-preview-request")

        assertEquals(NavigationContract.STATE_IDLE, result.navigationState)
        assertEquals(emptyList<Any>(), result.routePreview.routePoints)
    }

    @Test
    fun currentStateResult_mapsAuthoritativeErrorMessage() {
        val result = NavigationResultFactory {
            NavigationSessionState(
                status = NavigationSessionStatus.ERROR,
                destination = destination(),
                message = "OSRM route unavailable."
            )
        }.currentStateResult("error-request")

        assertEquals(NavigationContract.STATE_ERROR, result.navigationState)
        assertEquals("OSRM route unavailable.", result.message)
        assertEquals(-1L, result.etaSeconds)
        assertEquals(-1L, result.distanceMeters)
    }

    private fun destination(): ResolvedDestination =
        ResolvedDestination(
            id = "smart-village",
            place = Place(
                displayName = "Smart Village, Cairo-Alexandria Desert Road",
                latitude = 30.0726,
                longitude = 31.0174,
                category = "business_park",
                primaryName = "Smart Village",
                formattedAddress = "Cairo-Alexandria Desert Road"
            ),
            source = DestinationSource.SEARCH,
            distanceMeters = 25_000L
        )

    private fun routePlan(): RoutePlan =
        RoutePlan(
            alternatives = listOf(
                RouteAlternative(
                    points = listOf(
                        GeoPoint(30.0100, 31.0100),
                        GeoPoint(30.0400, 31.0150),
                        GeoPoint(30.0726, 31.0174)
                    ),
                    distanceMeters = 28_410.4,
                    durationSeconds = 1_924.6,
                    steps = emptyList()
                )
            )
        )
}
