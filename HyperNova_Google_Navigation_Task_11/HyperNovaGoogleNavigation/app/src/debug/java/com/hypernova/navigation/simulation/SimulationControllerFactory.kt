package com.hypernova.navigation.simulation

import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.Waypoint
import com.hypernova.navigation.NavigationRuntime
import com.hypernova.navigation.model.GoogleDestinationRecord

object SimulationControllerFactory {
    fun create(runtime: NavigationRuntime): SimulationController =
        object : SimulationController {
            override val available: Boolean
                get() = runtime.googleGatewayForDebug()?.navigatorForDebug() != null

            override fun startDeterministicDemo(): Boolean {
                val gateway = runtime.googleGatewayForDebug() ?: return false
                val navigator = gateway.navigatorForDebug() ?: return false
                val simulator = navigator.simulator ?: return false
                val origin = LatLng(30.0444, 31.2357)
                val destinationPoint = LatLng(30.0286, 31.2615)
                val destination =
                    GoogleDestinationRecord(
                        placeId = "debug-coordinate-only",
                        title = "HyperNova Cairo Demo",
                        subtitle = "Debug-only Google Navigation simulation",
                        category = "Simulation",
                        latitude = destinationPoint.latitude,
                        longitude = destinationPoint.longitude,
                    )
                val waypoint =
                    Waypoint.builder()
                        .setTitle(destination.title)
                        .setLatLng(destinationPoint.latitude, destinationPoint.longitude)
                        .build()
                navigator.stopGuidance()
                runtime.beginDebugSimulation(destination)
                simulator.setUserLocation(origin)
                navigator.setDestination(waypoint).setOnResultListener { status ->
                    if (status == Navigator.RouteStatus.OK) {
                        val route = gateway.currentRouteDataForDebug()
                        if (route == null) {
                            runtime.debugSimulationFailed("Google simulator route geometry is unavailable.")
                        } else {
                            if (gateway.startGuidance()) {
                                runtime.debugSimulationRouteReady(route)
                                simulator.simulateLocationsAlongExistingRoute(
                                    SimulationOptions().speedMultiplier(5f),
                                )
                            } else {
                                runtime.debugSimulationFailed("Google simulator guidance did not start.")
                            }
                        }
                    } else {
                        runtime.debugSimulationFailed("Google simulator could not calculate the demo route.")
                    }
                }
                return true
            }
        }
}
