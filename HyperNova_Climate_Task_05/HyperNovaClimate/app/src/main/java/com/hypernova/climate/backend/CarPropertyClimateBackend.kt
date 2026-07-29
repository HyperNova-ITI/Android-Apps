package com.hypernova.climate.backend

import android.util.Log
import com.hypernova.climate.model.ClimateConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Alternative backend — standard AAOS CarProperty / VHAL path.
 *
 * Uses `android.car.hardware.property.CarPropertyManager` to read/write HVAC
 * properties (HVAC_POWER_ON, HVAC_TEMPERATURE_SET, HVAC_FAN_SPEED, …). The real
 * vehicle values are bridged to the bare-metal TC397 *inside a custom VHAL*,
 * which owns the Ethernet link — see IMPLEMENTATION_PLAN.md §1.2. From the app's
 * point of view this is pure, standard AAOS.
 *
 * Not the default for now (the Ethernet backend is enabled). This class is kept
 * complete in structure so switching is a one-line build flag change:
 *   ./gradlew :app:assembleDebug -Pclimate.backend=VHAL
 */
class CarPropertyClimateBackend : ClimateBackend {

    override val id: String = "car-property"

    private val _connectionState =
        MutableStateFlow(ClimateConnectionState.IDLE)
    override val connectionState: StateFlow<ClimateConnectionState> =
        _connectionState.asStateFlow()

    override fun start() {
        Log.i(TAG, "Selected AAOS CarProperty/VHAL backend.")
        _connectionState.value = ClimateConnectionState.CONNECTING
        // TODO(car phase): create Car, obtain CarPropertyManager, register HVAC
        //   property callbacks, map property ids to internal ClimateState, then
        //   publish CONNECTED. The Ethernet transport lives in the VHAL, not here.
    }

    override fun stop() {
        // TODO(car phase): unregister callbacks, disconnect Car.
        _connectionState.value = ClimateConnectionState.IDLE
    }

    private companion object {
        const val TAG = "HN-CarPropertyClimate"
    }
}
