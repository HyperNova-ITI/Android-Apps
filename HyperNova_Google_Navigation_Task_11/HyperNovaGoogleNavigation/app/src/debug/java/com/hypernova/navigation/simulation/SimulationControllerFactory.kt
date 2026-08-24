package com.hypernova.navigation.simulation

import com.hypernova.navigation.NavigationRuntime

/** Route preview is real Google data; native Navigation SDK simulation is unavailable on AOSP. */
object SimulationControllerFactory {
    fun create(runtime: NavigationRuntime): SimulationController =
        object : SimulationController {
            override val available: Boolean = false
            override fun startDeterministicDemo(): Boolean = false
        }
}
