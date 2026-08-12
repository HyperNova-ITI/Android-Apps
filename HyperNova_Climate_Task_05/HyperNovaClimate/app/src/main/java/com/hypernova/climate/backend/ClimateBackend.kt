package com.hypernova.climate.backend

import com.hypernova.climate.model.ClimateConnectionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the vehicle climate implementation (README §38).
 *
 * The UI never knows which backend is active. Two implementations exist:
 *  - [VehicleGatewayClimateBackend] — typed AIDL link to the Android Gateway APK.
 *  - [CarPropertyClimateBackend]    — AAOS CarPropertyManager / VHAL.
 *
 * The [ClimateBackendFactory] selects one at runtime from the compiled-in
 * backend macro. This UI-phase interface exposes only the connection lifecycle;
 * Physical commands are submitted by the service/UI through [VehicleGatewayRuntime].
 */
interface ClimateBackend {

    /** Human-readable id for logging (e.g. "ethernet-gateway", "car-property"). */
    val id: String

    /** Observable link lifecycle. */
    val connectionState: StateFlow<ClimateConnectionState>

    /** Begin connecting to the vehicle backend. Safe to call more than once. */
    fun start()

    /** Release the connection and any resources. */
    fun stop()
}
