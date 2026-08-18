package com.hypernova.ai.session

import com.hypernova.ai.ui.NovaVisibleState

class NovaStateMachine(initialState: NovaVisibleState = NovaVisibleState.UNAVAILABLE) {
    var currentState: NovaVisibleState = initialState
        private set

    fun transitionTo(nextState: NovaVisibleState): Boolean {
        if (!isAllowed(currentState, nextState)) return false
        currentState = nextState
        return true
    }

    private fun isAllowed(from: NovaVisibleState, to: NovaVisibleState): Boolean {
        if (to == NovaVisibleState.UNAVAILABLE) return true
        if (from == NovaVisibleState.UNAVAILABLE) return to == NovaVisibleState.IDLE

        // Control and audio arrive on independent TCP readers. Their authoritative events can be
        // coalesced or observed in either order (for example LISTENING -> SPEAKING for the wake
        // acknowledgement). Once connected, every visible engine state may therefore replace the
        // current visible state, including an idempotent repeat.
        return true
    }
}
