package com.hypernova.navigation.simulation

import com.hypernova.navigation.NavigationRuntime

object SimulationControllerFactory {
    fun create(runtime: NavigationRuntime): SimulationController =
        object : SimulationController {
            override val available: Boolean = false
            override fun startDeterministicDemo(): Boolean = false
        }
}
