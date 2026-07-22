package com.hypernova.ai.runtime

import com.hypernova.ai.ui.NovaVisibleState

/**
 * Combines the independent control and audio sockets into one authoritative visible state.
 * Audio playback temporarily owns the screen; control progress continues to be remembered and is
 * revealed as soon as playback ends (for example wake chime -> PROCESSING).
 */
class NovaStateCoordinator {
    private var controlConnected = false
    private var audioConnected = false
    private var playing = false
    private var controlState = NovaVisibleState.IDLE

    @Synchronized
    fun onControlConnectionChanged(connected: Boolean): NovaVisibleState {
        val wasAvailable = isAvailable()
        controlConnected = connected
        resetWhenNewlyAvailable(wasAvailable)
        return visibleState()
    }

    @Synchronized
    fun onAudioConnectionChanged(connected: Boolean): NovaVisibleState {
        val wasAvailable = isAvailable()
        audioConnected = connected
        resetWhenNewlyAvailable(wasAvailable)
        return visibleState()
    }

    @Synchronized
    fun onControlState(state: NovaVisibleState): NovaVisibleState {
        controlState = state
        return visibleState()
    }

    @Synchronized
    fun onPlaybackChanged(isPlaying: Boolean): NovaVisibleState {
        playing = isPlaying
        return visibleState()
    }

    @Synchronized
    fun currentState(): NovaVisibleState = visibleState()

    private fun resetWhenNewlyAvailable(wasAvailable: Boolean) {
        if (!wasAvailable && isAvailable()) {
            controlState = NovaVisibleState.IDLE
            playing = false
        }
    }

    private fun isAvailable() = controlConnected && audioConnected

    private fun visibleState(): NovaVisibleState = when {
        !isAvailable() -> NovaVisibleState.UNAVAILABLE
        playing -> NovaVisibleState.SPEAKING
        else -> controlState
    }
}
