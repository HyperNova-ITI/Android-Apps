package com.hypernova.climate.backend

import android.content.Context
import com.hypernova.climate.model.ClimateConnectionState
import kotlinx.coroutines.flow.StateFlow

/** Climate facade over the process-wide typed Vehicle Gateway Binder client. */
class VehicleGatewayClimateBackend : ClimateBackend {
    override val id: String = "vehicle-gateway-aidl"
    override val connectionState: StateFlow<ClimateConnectionState> =
        VehicleGatewayRuntime.connectionState

    override fun start() = Unit // Application owns the binding, including NOVA-only launches.
    override fun stop() = Unit  // Do not tear down telemetry merely because the Activity stopped.

    companion object {
        fun startProcess(context: Context) = VehicleGatewayRuntime.start(context)
    }
}
