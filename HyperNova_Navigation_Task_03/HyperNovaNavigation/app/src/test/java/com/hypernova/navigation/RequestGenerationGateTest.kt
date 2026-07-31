package com.hypernova.navigation

import com.hypernova.navigation.domain.repository.RequestGenerationGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestGenerationGateTest {

    @Test
    fun newerRequestRejectsOlderCallback() {
        val gate = RequestGenerationGate()
        val older = gate.next()
        val current = gate.next()

        assertFalse(gate.isCurrent(older))
        assertTrue(gate.isCurrent(current))
    }

    @Test
    fun cancellationRejectsRunningCallback() {
        val gate = RequestGenerationGate()
        val running = gate.next()

        gate.cancel()

        assertFalse(gate.isCurrent(running))
    }
}
