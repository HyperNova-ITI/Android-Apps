package com.hypernova.navigation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRegistryTest {
    private val key = RequestKey(requestId = "req-1", operation = "set_destination")
    private val fingerprint = "place_A"
    private val accepted = "in-flight-token"
    private val callbackA = 1
    private val callbackB = 2

    @Test
    fun firstBeginForAKeyIsNew() {
        val registry = registry()

        val registration = registry.begin(key, fingerprint, accepted, callbackA)

        assertEquals(RequestRegistration.New, registration)
        assertEquals(1, registry.size())
    }

    @Test
    fun inFlightBeginWithSameFingerprintCoalescesAndAccumulatesCallbacks() {
        val registry = registry()
        registry.begin(key, fingerprint, accepted, callbackA)

        val merged = registry.begin(key, fingerprint, accepted, callbackB)

        assertEquals(RequestRegistration.InFlight(accepted), merged)
        assertEquals(listOf(callbackA, callbackB), registry.complete(key, "result"))
        assertNull(registry.complete(key, "result"))
    }

    @Test
    fun duplicateCallbackIsNotRegisteredTwice() {
        val registry = registry()
        registry.begin(key, fingerprint, accepted, callbackA)
        registry.begin(key, fingerprint, accepted, callbackA)

        assertEquals(listOf(callbackA), registry.complete(key, "result"))
    }

    @Test
    fun sameFingerprintKeyReplaysCompletedResult() {
        val registry = registry()
        registry.begin(key, fingerprint, accepted, callbackA)
        registry.complete(key, "final-result")

        val replay = registry.begin(key, fingerprint, accepted, callbackB)

        assertEquals(RequestRegistration.Completed("final-result"), replay)
    }

    @Test
    fun differentFingerprintForKeyIsAConflict() {
        val registry = registry()
        registry.begin(key, fingerprint, accepted, callbackA)

        val conflict = registry.begin(key, "other-place", accepted, callbackB)

        assertEquals(RequestRegistration.Conflict, conflict)
        assertEquals(listOf(callbackA), registry.complete(key, "result"))
    }

    @Test
    fun completeReturnsNullForUnknownKey() {
        val registry = registry()
        assertNull(registry.complete(key, "result"))
    }

    @Test
    fun inFlightEntryExpiresAfterRetentionWindow() {
        val clock = MutableClock(0L)
        val registry = RequestRegistry<String, Int>(retentionMillis = 10_000L, clockMillis = { clock.now })

        val first = registry.begin(key, fingerprint, accepted, callbackA)
        assertEquals(RequestRegistration.New, first)

        clock.now = 9_999L
        assertEquals(1, registry.size())

        clock.now = 10_000L
        assertEquals(0, registry.size())

        val second = registry.begin(key, fingerprint, accepted, callbackA)
        assertEquals(RequestRegistration.New, second)
    }

    @Test
    fun completedEntryCanBeReplayedUntilRetentionThenExpires() {
        val clock = MutableClock(0L)
        val registry = RequestRegistry<String, Int>(retentionMillis = 10_000L, clockMillis = { clock.now })

        registry.begin(key, fingerprint, accepted, callbackA)
        registry.complete(key, "final")

        clock.now = 9_999L
        assertEquals(RequestRegistration.Completed("final"), registry.begin(key, fingerprint, accepted, callbackB))

        clock.now = 10_000L
        assertEquals(RequestRegistration.New, registry.begin(key, fingerprint, accepted, callbackB))
    }

    private fun registry(retention: Long = 10_000L) =
        RequestRegistry<String, Int>(retentionMillis = retention)

    private class MutableClock(@JvmField var now: Long)
}