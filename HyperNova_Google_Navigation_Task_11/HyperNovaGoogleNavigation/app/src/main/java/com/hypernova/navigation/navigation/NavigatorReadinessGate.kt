package com.hypernova.navigation.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class NavigatorReadinessGate(initial: State = State.Waiting) {
    sealed interface State {
        data object Waiting : State
        data object Ready : State
        data class TerminalFailure(val failure: NavigatorInitializationFailure) : State
    }

    private val state = MutableStateFlow(initial)

    fun waiting() {
        if (state.value !is State.Ready) state.value = State.Waiting
    }

    fun ready() {
        state.value = State.Ready
    }

    fun terminal(failure: NavigatorInitializationFailure) {
        if (state.value !is State.Ready) state.value = State.TerminalFailure(failure)
    }

    suspend fun await(): State = state.first { it !is State.Waiting }
}
