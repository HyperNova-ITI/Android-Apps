package com.hypernova.climate.backend

/**
 * Standard Android 16 has no CarProperty/VHAL API. All climate requests use
 * the typed Vehicle Gateway AIDL and reach TC397 through QNX.
 */
object ClimateBackendFactory {
    fun create(): ClimateBackend =
        VehicleGatewayClimateBackend()
}
