package com.hypernova.climate.backend

import android.util.Log
import com.hypernova.climate.BuildConfig
import com.hypernova.climate.model.ClimateConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ENABLED backend for now — direct Ethernet link to the bare-metal TC397.
 *
 * Speaks the TC397 frame protocol (TC397-Networking.md):
 *   command channel  TCP  ${BuildConfig.TC397_HOST}:${BuildConfig.TC397_COMMAND_PORT}
 *   telemetry        UDP  :${BuildConfig.TC397_TELEMETRY_PORT}
 *   frame  CMD_TYPE | SEQ | LEN | PAYLOAD | CRC16(CCITT-FALSE) little-endian
 *
 * The actual socket client, frame codec, CRC, SEQ correlation and ACK/timeout
 * state machine are implemented in the transport phase under
 * `backend/transport/`. This class is the deployment-ready shell that the
 * factory wires in; it currently only advertises the connection lifecycle so
 * the UI can render honest states without any dummy vehicle data.
 */
class VehicleGatewayClimateBackend : ClimateBackend {

    override val id: String = "ethernet-gateway"

    private val _connectionState =
        MutableStateFlow(ClimateConnectionState.IDLE)
    override val connectionState: StateFlow<ClimateConnectionState> =
        _connectionState.asStateFlow()

    override fun start() {
        Log.i(TAG, "Selected TC397 direct-Ethernet backend " +
            "(${BuildConfig.TC397_HOST}:${BuildConfig.TC397_COMMAND_PORT}).")
        _connectionState.value = ClimateConnectionState.CONNECTING
        // TODO(transport phase): open TCP command socket + UDP telemetry listener,
        //   install frame codec/CRC, then publish CONNECTED on first authoritative
        //   readback. Until then the UI shows a connecting/unavailable state.
    }

    override fun stop() {
        // TODO(transport phase): close sockets, cancel scopes.
        _connectionState.value = ClimateConnectionState.IDLE
    }

    private companion object {
        const val TAG = "HN-VehicleGatewayClimate"
    }
}
