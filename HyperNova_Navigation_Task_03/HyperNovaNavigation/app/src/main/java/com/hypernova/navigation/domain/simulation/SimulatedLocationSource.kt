package com.hypernova.navigation.domain.simulation

import android.util.Log
import com.hypernova.navigation.domain.model.RouteAlternative
import com.hypernova.navigation.domain.model.VehiclePosition
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class SimulatedLocationSource(
    private val config: RouteSimulationConfig = RouteSimulationConfig(),
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }
) : LocationSource {
    private val lock = Any()
    private val listeners =
        CopyOnWriteArraySet<(VehiclePosition?) -> Unit>()
    private val controller = RouteSimulationController(config)

    @Volatile
    private var position: VehiclePosition? = null
    private var simulationFuture: ScheduledFuture<*>? = null
    private var generation = 0L
    private var closed = false

    override fun currentPosition(): VehiclePosition? = position

    override fun addListener(listener: (VehiclePosition?) -> Unit) {
        listeners += listener
    }

    override fun removeListener(listener: (VehiclePosition?) -> Unit) {
        listeners -= listener
    }

    override fun setRoute(route: RouteAlternative?) {
        val initialPosition: VehiclePosition?
        val hadSimulation: Boolean

        synchronized(lock) {
            if (closed) return
            hadSimulation = controller.isRunning
            generation++
            simulationFuture?.cancel(false)
            simulationFuture = null

            if (route == null || route.points.isEmpty()) {
                controller.cancel()
                position = null
                initialPosition = null
            } else {
                initialPosition = controller.start(route)
                position = initialPosition
                val geometryDistance =
                    RouteSimulationMath
                        .cumulativeDistances(route.points)
                        .lastOrNull()
                        ?: 0.0

                Log.i(
                    TAG,
                    "simulation=start routePoints=${route.points.size} " +
                        "distanceMeters=${geometryDistance.toLong()} " +
                        "durationSeconds=${route.durationSeconds.toLong()} " +
                        "speedFactor=${config.speedFactor}"
                )

                if (controller.isRunning) {
                    val activeGeneration = generation
                    simulationFuture =
                        scheduler.scheduleAtFixedRate(
                            { tick(activeGeneration) },
                            config.tickMillis,
                            config.tickMillis,
                            TimeUnit.MILLISECONDS
                        )
                }
            }
        }

        if (hadSimulation) {
            Log.i(
                TAG,
                if (route == null) {
                    "simulation=cancel"
                } else {
                    "simulation=cancel reason=route_replaced"
                }
            )
        }
        notifyListeners(initialPosition)

        if (initialPosition?.arrived == true) {
            Log.i(TAG, "simulation=arrived")
        }
    }

    private fun tick(expectedGeneration: Long) {
        val nextPosition: VehiclePosition

        synchronized(lock) {
            if (closed || expectedGeneration != generation) return
            nextPosition =
                controller.tick(
                    config.tickMillis / MILLIS_PER_SECOND
                ) ?: return
            position = nextPosition

            if (nextPosition.arrived) {
                simulationFuture?.cancel(false)
                simulationFuture = null
            }
        }

        notifyListeners(nextPosition)
        if (nextPosition.arrived) {
            Log.i(TAG, "simulation=arrived")
        }
    }

    private fun notifyListeners(vehiclePosition: VehiclePosition?) {
        listeners.forEach { listener ->
            runCatching { listener(vehiclePosition) }
                .onFailure {
                    Log.w(TAG, "simulation listener failed", it)
                }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            simulationFuture?.cancel(false)
            simulationFuture = null
            controller.cancel()
            position = null
        }
        listeners.clear()
        scheduler.shutdownNow()
        Log.i(TAG, "simulation=stop")
    }

    private companion object {
        const val TAG = "HyperNovaSimulation"
        const val THREAD_NAME = "hypernova-route-simulation"
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
