package com.hypernova.launcher.core.state

import com.hypernova.launcher.core.integration.AppAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedAppStateTest {
    @Test
    fun `installed available connected and active remain independent`() {
        val state = IntegratedAppState(
            availability = AppAvailability.AVAILABLE,
            connectionState = RuntimeConnectionState.CONNECTED,
            active = false,
        )

        assertEquals(true, state.installed)
        assertTrue(state.available)
        assertEquals(RuntimeConnectionState.CONNECTED, state.connectionState)
        assertFalse(state.active)
    }

    @Test
    fun `availability error does not claim installed`() {
        val state = IntegratedAppState(
            availability = AppAvailability.ERROR,
            connectionState = RuntimeConnectionState.ERROR,
            active = false,
        )

        assertNull(state.installed)
        assertFalse(state.available)
    }
}
