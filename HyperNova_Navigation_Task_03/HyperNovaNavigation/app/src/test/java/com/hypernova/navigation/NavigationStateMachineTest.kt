package com.hypernova.navigation

import com.hypernova.navigation.domain.model.NavigationScreen
import com.hypernova.navigation.ui.state.NavigationStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationStateMachineTest {

    @Test
    fun realRouteFlow_acceptsApprovedTransitions() {
        val machine = NavigationStateMachine()

        assertTrue(
            machine.transitionTo(NavigationScreen.SEARCH)
        )
        assertTrue(
            machine.transitionTo(NavigationScreen.SEARCHING)
        )
        assertTrue(
            machine.transitionTo(NavigationScreen.RESULTS)
        )
        assertTrue(
            machine.transitionTo(
                NavigationScreen.CALCULATING_ROUTE
            )
        )
        assertTrue(
            machine.transitionTo(
                NavigationScreen.ROUTE_PREVIEW
            )
        )
        assertTrue(
            machine.transitionTo(
                NavigationScreen.ROUTE_ACTIVE
            )
        )
        assertTrue(
            machine.transitionTo(
                NavigationScreen.ROUTE_OVERVIEW
            )
        )
    }

    @Test
    fun invalidProductionTransition_isRejected() {
        val machine = NavigationStateMachine()

        assertFalse(
            machine.transitionTo(
                NavigationScreen.ARRIVED
            )
        )
        assertEquals(
            NavigationScreen.HOME,
            machine.current
        )
    }

    @Test
    fun operationalInterruption_isAvailableFromHome() {
        val machine = NavigationStateMachine()

        assertTrue(
            machine.transitionTo(
                NavigationScreen.OFFLINE
            )
        )
    }
}
