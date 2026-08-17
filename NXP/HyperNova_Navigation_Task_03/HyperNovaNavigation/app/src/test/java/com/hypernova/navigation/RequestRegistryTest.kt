package com.hypernova.navigation

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.service.RequestKey
import com.hypernova.navigation.service.RequestRegistration
import com.hypernova.navigation.service.RequestRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRegistryTest {
    @Test
    fun duplicateSearchRequest_joinsInFlightAndReceivesFinal() {
        val registry =
            RequestRegistry<String, String>(
                retentionMillis = 600_000L
            )
        val key =
            RequestKey(
                "search-request",
                NavigationContract.OP_SEARCH_DESTINATIONS
            )

        assertTrue(
            registry.begin(
                key,
                "coffee",
                "accepted",
                "callback-1"
            ) is RequestRegistration.New
        )
        val duplicate =
            registry.begin(
                key,
                "coffee",
                "accepted",
                "callback-2"
            )
        assertEquals(
            "accepted",
            (duplicate as RequestRegistration.InFlight)
                .accepted
        )
        registry.begin(
            key,
            "coffee",
            "accepted",
            "callback-1"
        )

        val completion =
            registry.complete(key, "confirmed")
        assertEquals(
            listOf("callback-1", "callback-2"),
            completion?.callbacks
        )
        assertNull(
            registry.complete(key, "different-final")
        )
    }

    @Test
    fun duplicateRouteRequest_returnsCachedFinalWithoutNewWork() {
        val registry =
            RequestRegistry<String, String>(
                retentionMillis = 600_000L
            )
        val key =
            RequestKey(
                "route-request",
                NavigationContract.OP_SET_DESTINATION
            )

        registry.begin(
            key,
            "nav-destination",
            "accepted",
            "callback-1"
        )
        registry.complete(key, "confirmed")

        val duplicate =
            registry.begin(
                key,
                "nav-destination",
                "accepted",
                "callback-2"
            )

        assertEquals(
            "confirmed",
            (duplicate as RequestRegistration.Completed)
                .result
        )
    }

    @Test
    fun sameRequestAndOperationWithDifferentArguments_conflicts() {
        val registry =
            RequestRegistry<String, String>(
                retentionMillis = 600_000L
            )
        val key =
            RequestKey(
                "request",
                NavigationContract.OP_SET_DESTINATION
            )
        registry.begin(
            key,
            "nav-a",
            "accepted",
            "callback-1"
        )

        assertTrue(
            registry.begin(
                key,
                "nav-b",
                "accepted",
                "callback-2"
            ) is RequestRegistration.Conflict
        )
    }

    @Test
    fun finalResult_isCachedForConfiguredDeduplicationTtl() {
        var now = 0L
        val registry =
            RequestRegistry<String, String>(
                retentionMillis = 600_000L,
                clockMillis = { now }
            )
        val key =
            RequestKey(
                "cached-request",
                NavigationContract.OP_SEARCH_DESTINATIONS
            )
        registry.begin(
            key,
            "query",
            "accepted",
            "callback-1"
        )
        registry.complete(key, "confirmed")

        now = 599_999L
        assertTrue(
            registry.begin(
                key,
                "query",
                "accepted",
                "callback-2"
            ) is RequestRegistration.Completed
        )

        now = 600_000L
        assertTrue(
            registry.begin(
                key,
                "query",
                "accepted",
                "callback-3"
            ) is RequestRegistration.New
        )
    }
}
