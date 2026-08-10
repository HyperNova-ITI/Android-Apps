package com.hypernova.navigation

import com.hypernova.navigation.domain.model.DestinationResolution
import com.hypernova.navigation.domain.model.DestinationSource
import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.repository.DestinationStore
import com.hypernova.navigation.domain.repository.SavedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationStoreTest {
    @Test
    fun searchIssuesOpaqueId_andResolvesOriginalPlace() {
        val place =
            place(
                name = "Provider place",
                category = "hospital"
            )
        val store =
            store(idFactory = { "nav-token-1" })

        val issued =
            store.issueSearch(
                listOf(place),
                ORIGIN
            ).single()

        assertEquals("nav-token-1", issued.id)
        assertNotEquals(place.id, issued.id)
        assertFalse(issued.id.contains("30.1"))
        assertEquals(
            DestinationResolution.Found(issued),
            store.resolve(issued.id)
        )
    }

    @Test
    fun expiredSearchId_isDistinguishedFromUnknownId() {
        var now = 0L
        val store =
            store(
                clock = { now },
                idFactory = { "nav-expiring" }
            )
        val issued =
            store.issueSearch(
                listOf(place("Expiring", "fuel")),
                ORIGIN
            ).single()

        now = SEARCH_TTL_MILLIS

        assertEquals(
            DestinationResolution.Expired,
            store.resolve(issued.id)
        )
        assertEquals(
            DestinationResolution.Unknown,
            store.resolve("not-issued-by-navigation")
        )
    }

    @Test
    fun destinationMapping_isCategoryIndependent() {
        var nextId = 0
        val store =
            store {
                nextId += 1
                "nav-$nextId"
            }
        val categories =
            listOf(
                "fuel",
                "hospital",
                "restaurant",
                "parking",
                "shopping"
            )

        val issued =
            store.issueSearch(
                categories.map {
                    place(it, it)
                },
                ORIGIN
            )

        assertEquals(
            categories,
            issued.map { it.place.category }
        )
        assertTrue(
            issued.all {
                it.source == DestinationSource.SEARCH
            }
        )
    }

    @Test
    fun savedId_isStableWhileEntryExists_andUnknownAfterRemoval() {
        val store = store()
        val saved =
            SavedPlace(
                place("Home", "residential"),
                DestinationSource.SAVED_HOME
            )
        val first =
            store.refreshSaved(
                listOf(saved),
                ORIGIN
            ).single()
        val second =
            store.refreshSaved(
                listOf(saved),
                ORIGIN
            ).single()

        assertEquals(first.id, second.id)
        assertEquals(
            DestinationResolution.Found(second),
            store.resolve(second.id)
        )

        store.refreshSaved(emptyList(), ORIGIN)
        assertEquals(
            DestinationResolution.Unknown,
            store.resolve(second.id)
        )
    }

    private fun store(
        clock: () -> Long = { 0L },
        idFactory: () -> String = { "nav-default" }
    ): DestinationStore =
        DestinationStore(
            clockMillis = clock,
            idFactory = idFactory,
            searchTtlMillis = SEARCH_TTL_MILLIS,
            expiredRecordRetentionMillis =
                SEARCH_TTL_MILLIS
        )

    private fun place(
        name: String,
        category: String
    ): Place =
        Place(
            displayName = "$name, Cairo",
            latitude = 30.1,
            longitude = 31.1,
            category = category,
            providerId = "provider:$name"
        )

    companion object {
        private const val SEARCH_TTL_MILLIS = 600_000L
        private val ORIGIN = GeoPoint(30.0, 31.0)
    }
}
