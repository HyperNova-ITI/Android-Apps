package com.hypernova.climate

import android.app.Application
import com.hypernova.climate.backend.VehicleGatewayClimateBackend

/** Starts the process-wide bound gateway even when NOVA binds while the Climate UI is closed. */
class HyperNovaClimateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        VehicleGatewayClimateBackend.startProcess(this)
    }
}
