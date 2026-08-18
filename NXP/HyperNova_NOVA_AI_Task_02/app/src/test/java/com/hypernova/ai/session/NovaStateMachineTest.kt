package com.hypernova.ai.session

import com.hypernova.ai.ui.NovaVisibleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaStateMachineTest {
    @Test
    fun `approved command path reaches idle`() {
        val machine = NovaStateMachine(NovaVisibleState.IDLE)

        assertTrue(machine.transitionTo(NovaVisibleState.LISTENING))
        assertTrue(machine.transitionTo(NovaVisibleState.PROCESSING))
        assertTrue(machine.transitionTo(NovaVisibleState.EXECUTING))
        assertTrue(machine.transitionTo(NovaVisibleState.SUCCESS))
        assertTrue(machine.transitionTo(NovaVisibleState.SPEAKING))
        assertTrue(machine.transitionTo(NovaVisibleState.IDLE))
    }

    @Test
    fun `unavailable waits for a connected idle handshake`() {
        val machine = NovaStateMachine(NovaVisibleState.UNAVAILABLE)

        assertFalse(machine.transitionTo(NovaVisibleState.SUCCESS))
        assertEquals(NovaVisibleState.UNAVAILABLE, machine.currentState)
        assertTrue(machine.transitionTo(NovaVisibleState.IDLE))
    }

    @Test
    fun `connected states accept asynchronous audio and control order`() {
        val machine = NovaStateMachine(NovaVisibleState.IDLE)

        assertTrue(machine.transitionTo(NovaVisibleState.SPEAKING))
        assertTrue(machine.transitionTo(NovaVisibleState.LISTENING))
        assertTrue(machine.transitionTo(NovaVisibleState.PROCESSING))
        assertTrue(machine.transitionTo(NovaVisibleState.PROCESSING))
    }

    @Test
    fun `every state can become unavailable`() {
        NovaVisibleState.entries.forEach { initialState ->
            val machine = NovaStateMachine(initialState)
            assertTrue(machine.transitionTo(NovaVisibleState.UNAVAILABLE))
            assertEquals(NovaVisibleState.UNAVAILABLE, machine.currentState)
        }
    }
}
