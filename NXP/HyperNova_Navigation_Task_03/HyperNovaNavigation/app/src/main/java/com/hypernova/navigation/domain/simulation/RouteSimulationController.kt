package com.hypernova.navigation.domain.simulation

import com.hypernova.navigation.domain.model.RouteAlternative
import com.hypernova.navigation.domain.model.VehiclePosition

internal class RouteSimulationController(
    private val config: RouteSimulationConfig
) {
    private var engine: RouteSimulationEngine? = null

    var currentPosition: VehiclePosition? = null
        private set

    val isRunning: Boolean
        get() = engine != null

    fun start(route: RouteAlternative): VehiclePosition? {
        val nextEngine =
            route.takeIf { it.points.isNotEmpty() }
                ?.let { RouteSimulationEngine(it, config) }
        engine = nextEngine
        currentPosition = nextEngine?.initialPosition()
        if (currentPosition?.arrived == true) {
            engine = null
        }
        return currentPosition
    }

    fun tick(elapsedSeconds: Double): VehiclePosition? {
        val next = engine?.advance(elapsedSeconds) ?: return currentPosition
        currentPosition = next
        if (next.arrived) {
            engine = null
        }
        return next
    }

    fun cancel() {
        engine = null
        currentPosition = null
    }
}
