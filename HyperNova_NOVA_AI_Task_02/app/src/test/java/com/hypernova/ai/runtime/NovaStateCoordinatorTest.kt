package com.hypernova.ai.runtime

import com.hypernova.ai.ui.NovaVisibleState
import org.junit.Assert.assertEquals
import org.junit.Test

class NovaStateCoordinatorTest {
    @Test
    fun `both sockets are required before idle is available`() {
        val coordinator = NovaStateCoordinator()

        assertEquals(NovaVisibleState.UNAVAILABLE, coordinator.onControlConnectionChanged(true))
        assertEquals(NovaVisibleState.IDLE, coordinator.onAudioConnectionChanged(true))
    }

    @Test
    fun `wake acknowledgement does not erase processing state`() {
        val coordinator = connectedCoordinator()

        assertEquals(NovaVisibleState.LISTENING, coordinator.onControlState(NovaVisibleState.LISTENING))
        assertEquals(NovaVisibleState.SPEAKING, coordinator.onPlaybackChanged(true))
        assertEquals(NovaVisibleState.SPEAKING, coordinator.onControlState(NovaVisibleState.PROCESSING))
        assertEquals(NovaVisibleState.PROCESSING, coordinator.onPlaybackChanged(false))
    }

    @Test
    fun `audio playback has priority over control states`() {
        val coordinator = connectedCoordinator()

        coordinator.onControlState(NovaVisibleState.EXECUTING)
        assertEquals(NovaVisibleState.SPEAKING, coordinator.onPlaybackChanged(true))
        assertEquals(NovaVisibleState.SPEAKING, coordinator.onControlState(NovaVisibleState.SUCCESS))
        assertEquals(NovaVisibleState.SUCCESS, coordinator.onPlaybackChanged(false))
    }

    @Test
    fun `either socket disconnect makes the runtime unavailable`() {
        val coordinator = connectedCoordinator()

        coordinator.onControlState(NovaVisibleState.PROCESSING)
        assertEquals(NovaVisibleState.UNAVAILABLE, coordinator.onAudioConnectionChanged(false))
    }

    private fun connectedCoordinator() = NovaStateCoordinator().apply {
        onControlConnectionChanged(true)
        onAudioConnectionChanged(true)
    }
}
