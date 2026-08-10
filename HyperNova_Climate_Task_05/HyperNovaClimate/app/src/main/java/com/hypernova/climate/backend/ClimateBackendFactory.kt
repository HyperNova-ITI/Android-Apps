package com.hypernova.climate.backend

import android.util.Log
import com.hypernova.climate.config.BackendMode

/**
 * Single place that turns the compiled-in backend macro into a concrete
 * [ClimateBackend]. Nothing else in the app decides which backend is used.
 */
object ClimateBackendFactory {

    private const val TAG = "HN-Climate"

    fun create(): ClimateBackend {
        val mode = BackendMode.current
        Log.i(TAG, "Creating climate backend for mode=$mode")
        return when (mode) {
            BackendMode.ETHERNET -> VehicleGatewayClimateBackend()
            BackendMode.VHAL -> CarPropertyClimateBackend()
        }
    }
}
