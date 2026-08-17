package com.hypernova.navigation

import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.FailureKind
import com.hypernova.navigation.domain.model.NavigationDataException
import com.hypernova.navigation.domain.repository.NearbyResultAccumulator
import com.hypernova.navigation.domain.repository.NearbySearchResult
import com.hypernova.navigation.domain.repository.NearbySearchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbySearchPolicyTest {

    @Test
    fun progressiveRadii_matchProductPolicy() {
        assertEquals(
            listOf(5_000, 10_000, 25_000, 50_000),
            NearbySearchPolicy.RADII_METERS
        )
    }

    @Test
    fun searchStopsAtTenUsefulResults() {
        assertFalse(
            NearbySearchPolicy.shouldStop(
                usefulResultCount = 9,
                radiusIndex = 0
            )
        )
        assertTrue(
            NearbySearchPolicy.shouldStop(
                usefulResultCount = 10,
                radiusIndex = 0
            )
        )
    }

    @Test
    fun noResultsStopAfterMaximumRadius() {
        assertTrue(
            NearbySearchPolicy.shouldStop(
                usefulResultCount = 0,
                radiusIndex =
                    NearbySearchPolicy.RADII_METERS.lastIndex
            )
        )
    }

    @Test
    fun displayResults_areDistanceSortedAndCapped() {
        val places =
            (1..35).map { index ->
                Place(
                    displayName = "Place $index",
                    latitude = 30.0,
                    longitude = 31.0,
                    straightLineDistanceMeters =
                        (36 - index).toDouble()
                )
            }
        val displayed =
            NearbySearchPolicy.displayResults(places)

        assertEquals(30, displayed.size)
        assertEquals(1.0, displayed.first().straightLineDistanceMeters)
        assertEquals(30.0, displayed.last().straightLineDistanceMeters)
    }

    @Test
    fun smallerRadiusResultsSurviveLaterProviderFailure() {
        val accumulator = NearbyResultAccumulator()
        val places =
            listOf(
                Place(
                    displayName = "Real fuel station",
                    latitude = 30.07,
                    longitude = 31.02
                )
            )
        accumulator.recordSuccess(places, 5_000)

        val outcome =
            accumulator.completeAfterFailure(
                NavigationDataException(
                    kind = FailureKind.TIMEOUT,
                    message = "timeout",
                    retryable = true
                )
            ).getOrThrow()

        assertEquals(places, outcome.places)
        assertEquals(5_000, outcome.finalRadiusMeters)
        assertTrue(outcome.widerSearchUnavailable)
        assertEquals(
            FailureKind.TIMEOUT,
            outcome.partialFailure?.kind
        )
    }

    @Test
    fun providerFailureWithoutEarlierResultsRemainsFailure() {
        val outcome =
            NearbyResultAccumulator().completeAfterFailure(
                NavigationDataException(
                    kind = FailureKind.PROVIDER,
                    message = "provider unavailable",
                    retryable = true
                )
            )

        assertTrue(outcome.isFailure)
    }

    @Test
    fun successfulZeroResultsAtMaximumRadiusIsNoResults() {
        val accumulator = NearbyResultAccumulator()
        accumulator.recordSuccess(emptyList(), 50_000)

        val outcome = accumulator.complete()

        assertTrue(outcome.places.isEmpty())
        assertEquals(50_000, outcome.finalRadiusMeters)
        assertFalse(outcome.widerSearchUnavailable)
    }

    @Test
    fun providerFailuresAreNeverCachedAsSuccessfulEmptyResults() {
        assertFalse(
            NearbySearchPolicy.shouldCache(
                NearbySearchResult(
                    places = emptyList(),
                    finalRadiusMeters = 50_000
                )
            )
        )
    }
}
