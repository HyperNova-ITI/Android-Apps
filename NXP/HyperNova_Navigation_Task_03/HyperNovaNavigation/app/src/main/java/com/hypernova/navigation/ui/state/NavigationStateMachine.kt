package com.hypernova.navigation.ui.state

import com.hypernova.navigation.domain.model.NavigationScreen

class NavigationStateMachine(
    initialState: NavigationScreen = NavigationScreen.HOME
) {
    var current: NavigationScreen = initialState
        private set

    fun transitionTo(
        target: NavigationScreen,
        debugOverride: Boolean = false
    ): Boolean {
        if (
            debugOverride ||
            target == current ||
            target in allowedTransitions.getValue(current)
        ) {
            current = target
            return true
        }

        return false
    }

    companion object {
        private val operationalInterruptions =
            setOf(
                NavigationScreen.LOCATION_UNAVAILABLE,
                NavigationScreen.OFFLINE,
                NavigationScreen.ROUTE_ERROR
            )

        private val allowedTransitions =
            NavigationScreen.entries.associateWith { screen ->
                val stateSpecific =
                    when (screen) {
                        NavigationScreen.HOME ->
                            setOf(
                                NavigationScreen.SEARCH,
                                NavigationScreen.CALCULATING_ROUTE
                            )
                        NavigationScreen.SEARCH ->
                            setOf(
                                NavigationScreen.HOME,
                                NavigationScreen.SEARCHING,
                                NavigationScreen.RESULTS
                            )
                        NavigationScreen.SEARCHING ->
                            setOf(
                                NavigationScreen.SEARCH,
                                NavigationScreen.RESULTS,
                                NavigationScreen.HOME
                            )
                        NavigationScreen.RESULTS ->
                            setOf(
                                NavigationScreen.SEARCH,
                                NavigationScreen.CALCULATING_ROUTE,
                                NavigationScreen.HOME
                            )
                        NavigationScreen.CALCULATING_ROUTE ->
                            setOf(
                                NavigationScreen.ROUTE_PREVIEW,
                                NavigationScreen.RESULTS,
                                NavigationScreen.SEARCH,
                                NavigationScreen.HOME
                            )
                        NavigationScreen.ROUTE_PREVIEW ->
                            setOf(
                                NavigationScreen.ROUTE_ACTIVE,
                                NavigationScreen.ROUTE_OVERVIEW,
                                NavigationScreen.SEARCH,
                                NavigationScreen.HOME
                            )
                        NavigationScreen.ROUTE_ACTIVE ->
                            setOf(
                                NavigationScreen.ROUTE_OVERVIEW,
                                NavigationScreen.REROUTING,
                                NavigationScreen.ARRIVED,
                                NavigationScreen.ROUTE_PREVIEW,
                                NavigationScreen.HOME
                            )
                        NavigationScreen.ROUTE_OVERVIEW ->
                            setOf(
                                NavigationScreen.ROUTE_ACTIVE,
                                NavigationScreen.ROUTE_PREVIEW,
                                NavigationScreen.HOME
                            )
                        NavigationScreen.REROUTING ->
                            setOf(
                                NavigationScreen.ROUTE_ACTIVE,
                                NavigationScreen.ROUTE_ERROR,
                                NavigationScreen.HOME
                            )
                        NavigationScreen.ARRIVED ->
                            setOf(NavigationScreen.HOME)
                        NavigationScreen.LOCATION_UNAVAILABLE ->
                            setOf(
                                NavigationScreen.HOME,
                                NavigationScreen.SEARCH
                            )
                        NavigationScreen.OFFLINE ->
                            setOf(
                                NavigationScreen.HOME,
                                NavigationScreen.SEARCH,
                                NavigationScreen.RESULTS,
                                NavigationScreen.ROUTE_PREVIEW,
                                NavigationScreen.ROUTE_ACTIVE
                            )
                        NavigationScreen.ROUTE_ERROR ->
                            setOf(
                                NavigationScreen.CALCULATING_ROUTE,
                                NavigationScreen.SEARCH,
                                NavigationScreen.RESULTS,
                                NavigationScreen.HOME
                            )
                    }

                stateSpecific + operationalInterruptions
            }
    }
}
