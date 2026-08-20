package com.hypernova.navigation.simulation

interface SimulationController {
    val available: Boolean
    fun startDeterministicDemo(): Boolean
}
