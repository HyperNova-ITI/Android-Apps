package com.hypernova.navigation.domain.repository

import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.domain.model.DestinationResolution
import com.hypernova.navigation.domain.model.DestinationSource
import com.hypernova.navigation.domain.model.GeoDistance
import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.ResolvedDestination
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToLong

class DestinationStore(
    private val clockMillis: () -> Long =
        System::currentTimeMillis,
    private val idFactory: () -> String = {
        "nav-${UUID.randomUUID()}"
    },
    private val searchTtlMillis: Long =
        NavigationContract.SEARCH_RESULT_TTL_MILLIS,
    private val expiredRecordRetentionMillis: Long =
        HyperNovaContract.REQUEST_DEDUP_TTL_MILLIS
) {
    private val searchRecords =
        linkedMapOf<String, SearchRecord>()
    private val savedRecords =
        linkedMapOf<String, ResolvedDestination>()

    @Synchronized
    fun issueSearch(
        places: List<Place>,
        origin: GeoPoint?
    ): List<ResolvedDestination> {
        removeOldExpiredRecords()
        val expiresAt = clockMillis() + searchTtlMillis

        return places.map { place ->
            val destination =
                resolved(
                    id = uniqueSearchId(),
                    place = place,
                    source = DestinationSource.SEARCH,
                    origin = origin
                )
            searchRecords[destination.id] =
                SearchRecord(destination, expiresAt)
            destination
        }
    }

    @Synchronized
    fun refreshSaved(
        places: List<SavedPlace>,
        origin: GeoPoint?
    ): List<ResolvedDestination> {
        savedRecords.clear()

        return places.map { saved ->
            resolved(
                id = stableSavedId(saved),
                place = saved.place,
                source = saved.source,
                origin = origin
            ).also { destination ->
                savedRecords[destination.id] = destination
            }
        }
    }

    @Synchronized
    fun resolve(destinationId: String): DestinationResolution {
        removeOldExpiredRecords()

        searchRecords[destinationId]?.let { record ->
            return if (clockMillis() < record.expiresAtMillis) {
                DestinationResolution.Found(record.destination)
            } else {
                DestinationResolution.Expired
            }
        }

        savedRecords[destinationId]?.let {
            return DestinationResolution.Found(it)
        }

        return DestinationResolution.Unknown
    }

    private fun uniqueSearchId(): String {
        var candidate = idFactory()
        while (
            candidate.isBlank() ||
            candidate in searchRecords ||
            candidate in savedRecords
        ) {
            candidate = idFactory()
        }
        return candidate
    }

    private fun resolved(
        id: String,
        place: Place,
        source: DestinationSource,
        origin: GeoPoint?
    ): ResolvedDestination =
        ResolvedDestination(
            id = id,
            place = place,
            source = source,
            distanceMeters =
                place.straightLineDistanceMeters
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?.roundToLong()
                    ?: origin?.let {
                        GeoDistance.meters(
                            from = it,
                            to =
                                GeoPoint(
                                    latitude = place.latitude,
                                    longitude = place.longitude
                                )
                        ).roundToLong()
                    }
        )

    private fun stableSavedId(saved: SavedPlace): String {
        val identity =
            listOf(
                saved.source.name,
                saved.place.id,
                saved.place.latitude.toString(),
                saved.place.longitude.toString()
            ).joinToString("|")
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    identity.toByteArray(StandardCharsets.UTF_8)
                )
                .joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
                .take(SAVED_ID_DIGEST_LENGTH)

        return "nav-$digest"
    }

    private fun removeOldExpiredRecords() {
        val oldestRetainedExpiry =
            clockMillis() - expiredRecordRetentionMillis
        searchRecords.entries.removeAll {
            it.value.expiresAtMillis < oldestRetainedExpiry
        }
    }

    private data class SearchRecord(
        val destination: ResolvedDestination,
        val expiresAtMillis: Long
    )

    companion object {
        private const val SAVED_ID_DIGEST_LENGTH = 32
    }
}
