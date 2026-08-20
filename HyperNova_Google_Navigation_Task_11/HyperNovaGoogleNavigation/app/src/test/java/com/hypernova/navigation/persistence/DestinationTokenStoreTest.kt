package com.hypernova.navigation.persistence

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.core.Clock
import com.hypernova.navigation.model.GoogleDestinationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationTokenStoreTest {
    @Test
    fun searchTokensAreOpaqueIdsWithSourceAndTtl() {
        val persistence = InMemoryPersistence()
        val clock = MutableClock(1_000L)
        val store = DestinationTokenStore(persistence, clock)

        val created = store.createSearchTokens(records(3))

        assertEquals(3, created.size)
        created.forEach { entry ->
            assertTrue(entry.token.startsWith("nav_search_"))
            assertTrue(entry.token.length > "nav_search_".length)
            assertEquals(NavigationContract.SOURCE_SEARCH, entry.source)
            assertEquals(1_000L, entry.createdAtMillis)
            assertEquals(1_000L + NavigationContract.SEARCH_RESULT_TTL_MILLIS, entry.expiresAtMillis)
        }
        assertEquals(3, created.map { it.token }.toSet().size)
        assertTrue(persistence.entries.isNotEmpty())
    }

    @Test
    fun searchTokensAreCappedAtMaximumDestinationResults() {
        val store = DestinationTokenStore(InMemoryPersistence(), MutableClock(0L))
        val created = store.createSearchTokens(records(10))

        assertEquals(NavigationContract.MAX_DESTINATION_RESULTS, created.size)
        assertEquals(NavigationContract.MAX_DESTINATION_RESULTS.toLong(), store.allEntriesForTesting().size.toLong())
    }

    @Test
    fun resolveDistinguishesFoundExpiredAndUnknown() {
        val clock = MutableClock(0L)
        val store = DestinationTokenStore(InMemoryPersistence(), clock)

        val token = store.createSearchTokens(records(1)).single().token
        val unknown = "nav_search_unknown"

        assertEquals(DestinationResolution.Unknown, store.resolve(unknown))
        assertTrue(store.resolve(token) is DestinationResolution.Found)

        clock.now += NavigationContract.SEARCH_RESULT_TTL_MILLIS
        assertEquals(DestinationResolution.Expired, store.resolve(token))

        clock.now += 1L
        assertEquals(DestinationResolution.Expired, store.resolve(token))
    }

    @Test
    fun savedTokensNeverExpire() {
        val clock = MutableClock(0L)
        val store = DestinationTokenStore(InMemoryPersistence(), clock)

        val entry = store.putSaved(record("home_1"), NavigationContract.SOURCE_SAVED_HOME)
        clock.now = Long.MAX_VALUE / 2
        assertTrue(store.resolve(entry.token) is DestinationResolution.Found)
    }

    @Test
    fun savedTokensAreStablePerSourceAndPlaceId() {
        val store = DestinationTokenStore(InMemoryPersistence(), MutableClock(0L))

        val home = store.putSaved(record("place_A"), NavigationContract.SOURCE_SAVED_HOME)
        val homeAgain = store.putSaved(record("place_A"), NavigationContract.SOURCE_SAVED_HOME)
        val favorite = store.putSaved(record("place_A"), NavigationContract.SOURCE_SAVED_FAVORITE)
        val otherPlace =
            store.putSaved(record("place_B"), NavigationContract.SOURCE_SAVED_HOME).token

        assertEquals(home.token, homeAgain.token)
        assertNotEquals(home.token, favorite.token)
        assertNotEquals(home.token, otherPlace)
        assertTrue(home.token.startsWith("nav_saved_"))

        assertEquals(3, store.allEntriesForTesting().size)
        assertEquals(3, store.saved().size)
    }

    @Test
    fun savedSourceMustBeHomeWorkOrFavorite() {
        val store = DestinationTokenStore(InMemoryPersistence(), MutableClock(0L))
        assertThrows(IllegalArgumentException::class.java) {
            store.putSaved(record("place_A"), NavigationContract.SOURCE_SEARCH)
        }
    }

    @Test
    fun durableReloadRestoresSearchAndSavedTokens() {
        val persistence = InMemoryPersistence()
        val clock = MutableClock(10_000L)
        val first = DestinationTokenStore(persistence, clock)

        val searchToken = first.createSearchTokens(records(1)).single().token
        val saved = first.putSaved(record("place_A"), NavigationContract.SOURCE_SAVED_WORK)

        val reloaded = DestinationTokenStore(persistence, clock)

        val searchResolution = reloaded.resolve(searchToken)
        assertTrue(searchResolution is DestinationResolution.Found)
        assertEquals(searchToken, (searchResolution as DestinationResolution.Found).entry.token)

        val savedResolution = reloaded.resolve(saved.token)
        assertTrue(savedResolution is DestinationResolution.Found)
        val foundSaved =
            savedResolution as DestinationResolution.Found
        assertEquals(saved.token, foundSaved.entry.token)
        assertEquals(saved.record, foundSaved.entry.record)
        assertEquals(NavigationContract.SOURCE_SAVED_WORK, foundSaved.entry.source)
        assertEquals(null, foundSaved.entry.expiresAtMillis)
    }

    @Test
    fun expiredTombstonesArePrunedOnReloadOnceOlderThanRetention() {
        val persistence = InMemoryPersistence()
        val clock = MutableClock(0L)
        val first = DestinationTokenStore(persistence, clock)
        val searchToken = first.createSearchTokens(records(1)).single().token

        val farFuture =
            NavigationContract.SEARCH_RESULT_TTL_MILLIS +
                (24 * 60 * 60 * 1000L) +
                1L
        clock.now = farFuture
        assertEquals(DestinationResolution.Expired, first.resolve(searchToken))

        val reloaded = DestinationTokenStore(persistence, clock)
        assertEquals(DestinationResolution.Unknown, reloaded.resolve(searchToken))
    }

    @Test
    fun savedDestinationsAreSortedBySourceAndExcludeSearch() {
        val store =
            DestinationTokenStore(
                InMemoryPersistence(),
                MutableClock(0L),
            )
        store.createSearchTokens(records(2))
        store.putSaved(record("place_A"), NavigationContract.SOURCE_SAVED_HOME)
        store.putSaved(record("place_B"), NavigationContract.SOURCE_SAVED_FAVORITE)
        store.putSaved(record("place_C"), NavigationContract.SOURCE_SAVED_WORK)

        val sources = store.saved().map { it.source }
        assertEquals(3, sources.size)
        assertEquals(
            listOf(
                NavigationContract.SOURCE_SAVED_HOME,
                NavigationContract.SOURCE_SAVED_WORK,
                NavigationContract.SOURCE_SAVED_FAVORITE,
            ),
            sources,
        )
    }

    private fun records(count: Int): List<GoogleDestinationRecord> =
        List(count) { record("place_$it") }

    private fun record(placeId: String): GoogleDestinationRecord =
        GoogleDestinationRecord(
            placeId = placeId,
            title = "Title $placeId",
            subtitle = "Subtitle",
            category = "Category",
            latitude = 30.0,
            longitude = 31.0,
        )

    private class InMemoryPersistence : DestinationTokenPersistence {
        var entries: List<DestinationTokenEntry> = emptyList()

        override fun load(): List<DestinationTokenEntry> = entries

        override fun save(entries: List<DestinationTokenEntry>) {
            this.entries = entries
        }
    }

    private class MutableClock(var now: Long) : Clock {
        override fun nowMillis(): Long = now
    }
}