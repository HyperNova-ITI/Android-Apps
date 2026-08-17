package com.hypernova.climate.backend

import com.hypernova.climate.model.ClimateConnectionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the vehicle climate implementation.
 *
 * The standard Android 16 deployment has one implementation:
 * [VehicleGatewayClimateBackend], a typed AIDL link to the Android Gateway APK.
 * This UI-phase interface exposes only the connection lifecycle; physical
 * commands are submitted by the service/UI through [VehicleGatewayRuntime].
 */
interface ClimateBackend {

    /** Human-readable id for logging. */
    val id: String

    /** Observable link lifecycle. */
    val connectionState: StateFlow<ClimateConnectionState>

    /** Begin connecting to the vehicle backend. Safe to call more than once. */
    fun start()

    /** Release the connection and any resources. */
    fun stop()
}
