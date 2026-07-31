package com.hypernova.navigation.domain.repository

import com.hypernova.navigation.domain.model.NearbyCategory
import com.hypernova.navigation.domain.model.NavigationDataException
import com.hypernova.navigation.domain.model.Place

data class NearbySearchProgress(
    val category: NearbyCategory,
    val radiusMeters: Int,
    val isExpansion: Boolean,
    val generationId: Int = 0
)

data class NearbySearchResult(
    val places: List<Place>,
    val finalRadiusMeters: Int,
    val widerSearchUnavailable: Boolean = false,
    val partialFailure: NavigationDataException? = null,
    val fromCache: Boolean = false
)

object NearbySearchPolicy {
    val RADII_METERS =
        listOf(
            5_000,
            10_000,
            25_000,
            50_000
        )

    fun shouldStop(
        usefulResultCount: Int,
        radiusIndex: Int
    ): Boolean =
        usefulResultCount >= MIN_USEFUL_RESULTS ||
            radiusIndex >= RADII_METERS.lastIndex

    fun displayResults(places: List<Place>): List<Place> =
        places
            .sortedBy {
                it.straightLineDistanceMeters
                    ?: Double.POSITIVE_INFINITY
            }
            .take(MAX_DISPLAYED_RESULTS)

    fun shouldUseCachedResultAsTerminal(
        resultCount: Int,
        radiusMeters: Int
    ): Boolean =
        resultCount >= MIN_USEFUL_RESULTS ||
            radiusMeters >= MAX_RADIUS_METERS

    fun shouldCache(result: NearbySearchResult): Boolean =
        result.places.isNotEmpty()

    const val MIN_USEFUL_RESULTS = 10
    const val MAX_DISPLAYED_RESULTS = 30
    const val MAX_RADIUS_METERS = 50_000
}

internal class NearbyResultAccumulator {
    private var latest =
        NearbySearchResult(
            places = emptyList(),
            finalRadiusMeters =
                NearbySearchPolicy.RADII_METERS.last()
        )

    fun recordSuccess(
        places: List<Place>,
        radiusMeters: Int
    ) {
        latest =
            NearbySearchResult(
                places =
                    NearbySearchPolicy.displayResults(places),
                finalRadiusMeters = radiusMeters
            )
    }

    fun complete(): NearbySearchResult = latest

    fun completeAfterFailure(
        failure: NavigationDataException
    ): Result<NearbySearchResult> =
        if (latest.places.isNotEmpty()) {
            Result.success(
                latest.copy(
                    widerSearchUnavailable = true,
                    partialFailure = failure
                )
            )
        } else {
            Result.failure(failure)
        }
}
