package com.hypernova.phone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsLoadGateTest {
    @Test fun repeatedRequestsShareOneInFlightLoad() {
        val gate = RecentsLoadGate()
        val first = gate.begin()
        assertTrue(first != null)
        assertNull(gate.begin())
        gate.complete(first!!)
        assertTrue(gate.begin() != null)
    }

    @Test fun olderResultCannotOverwriteForcedNewerRequest() {
        val gate = RecentsLoadGate()
        val old = gate.begin()!!
        val newer = gate.begin(force = true)!!
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(newer))
        gate.complete(old)
        assertTrue(gate.isCurrent(newer))
        gate.complete(newer)
        assertEquals(3L, gate.begin())
    }
}
