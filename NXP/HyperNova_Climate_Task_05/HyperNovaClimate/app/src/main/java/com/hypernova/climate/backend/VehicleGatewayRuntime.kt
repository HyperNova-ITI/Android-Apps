package com.hypernova.climate.backend

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.hypernova.climate.data.ClimateStateOwner
import com.hypernova.climate.model.AcMode
import com.hypernova.climate.model.ClimateAvailability
import com.hypernova.climate.model.ClimateCapabilities
import com.hypernova.climate.model.ClimateConnectionState
import com.hypernova.climate.model.ClimateHealth
import com.hypernova.climate.model.ClimateMode
import com.hypernova.climate.model.ClimateState
import com.hypernova.climate.model.ClimateZoneMode
import com.hypernova.climate.ui.state.ClimateRequestedState
import com.hypernova.climate.ui.state.ClimateUiState
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.vehiclegateway.IVehicleGatewayCallback
import com.hypernova.contracts.vehiclegateway.IVehicleGatewayService
import com.hypernova.contracts.vehiclegateway.IVehicleStateListener
import com.hypernova.contracts.vehiclegateway.VehicleClimateCommand
import com.hypernova.contracts.vehiclegateway.VehicleFaultEvent
import com.hypernova.contracts.vehiclegateway.VehicleGatewayContract
import com.hypernova.contracts.vehiclegateway.VehicleGatewayResult
import com.hypernova.contracts.vehiclegateway.VehicleState
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** One process-wide typed Binder client. It never opens a TC397 or QNX socket. */
object VehicleGatewayRuntime {
    private const val TAG = "HN-ClimateGateway"

    private val lock = Any()
    private var appContext: Context? = null
    private var gateway: IVehicleGatewayService? = null
    private var binding = false
    private var bound = false

    private val mutableConnection = MutableStateFlow(ClimateConnectionState.IDLE)
    val connectionState: StateFlow<ClimateConnectionState> = mutableConnection.asStateFlow()

    val capabilities = ClimateCapabilities(
        zoneMode = ClimateZoneMode.DUAL,
        minimumTemperatureC = 16f,
        maximumTemperatureC = 28f,
        temperatureStepC = 1f,
        maximumFanLevel = 5,
        supportsAutoMode = false,
        supportsAc = false,
        supportsHeating = false,
        supportsZoneSync = false,
        supportsCabinTemperature = true,
    )

    private val stateListener = object : IVehicleStateListener.Stub() {
        override fun onVehicleState(state: VehicleState?) {
            if (state != null) publish(state)
        }

        override fun onFault(event: VehicleFaultEvent?) {
            if (event != null) {
                Log.i(TAG, "DTC ${event.dtc.toString(16)} active=${event.isActive}")
            }
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        disconnected("Gateway process died")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = IVehicleGatewayService.Stub.asInterface(binder)
            synchronized(lock) {
                gateway = service
                binding = false
                bound = true
            }
            try {
                binder?.linkToDeath(deathRecipient, 0)
                service.registerVehicleStateListener(stateListener)
                mutableConnection.value = ClimateConnectionState.CONNECTING
                service.latestVehicleState?.let(::publish)
            } catch (error: RemoteException) {
                Log.w(TAG, "Gateway disappeared during registration", error)
                disconnected("Vehicle gateway unavailable")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            disconnected("Vehicle gateway disconnected")
            // The binding is retained; Android reconnects it when the service returns.
        }

        override fun onBindingDied(name: ComponentName?) {
            disconnected("Vehicle gateway binding died")
            releaseDeadBinding()
            bindIfNeeded()
        }

        override fun onNullBinding(name: ComponentName?) {
            disconnected("Vehicle gateway rejected the bind")
            releaseDeadBinding()
        }
    }

    fun start(context: Context) {
        val firstStart = synchronized(lock) {
            if (appContext == null) {
                appContext = context.applicationContext
                true
            } else {
                false
            }
        }
        if (firstStart) publishUnavailable("Connecting to vehicle gateway")
        bindIfNeeded()
    }

    private fun bindIfNeeded() {
        val context = synchronized(lock) {
            if (gateway != null || binding || bound) return
            binding = true
            appContext
        } ?: return
        mutableConnection.value = ClimateConnectionState.CONNECTING
        val intent = Intent(VehicleGatewayContract.BIND_ACTION).setComponent(
            ComponentName(
                VehicleGatewayContract.PACKAGE_NAME,
                VehicleGatewayContract.COMMAND_SERVICE,
            ),
        )
        if (context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            synchronized(lock) { bound = true }
        } else {
            synchronized(lock) {
                binding = false
                bound = false
            }
            disconnected("Vehicle gateway APK is not installed")
        }
    }

    fun submit(
        requestId: String,
        targetTemperatureC: Int,
        fanLevel: Int,
        zone: Int,
        caller: Int,
        callback: (VehicleGatewayResult) -> Unit,
    ): Boolean {
        val service = synchronized(lock) { gateway } ?: return false
        return try {
            service.submitClimateCommand(
                VehicleClimateCommand(requestId, targetTemperatureC, fanLevel, zone, caller),
                object : IVehicleGatewayCallback.Stub() {
                    override fun onResult(result: VehicleGatewayResult?) {
                        if (result != null) {
                            result.confirmedState?.let(::publish)
                            callback(result)
                        }
                    }
                },
            )
            true
        } catch (error: RemoteException) {
            Log.w(TAG, "Vehicle command Binder call failed", error)
            disconnected("Vehicle gateway unavailable")
            false
        }
    }

    fun setPowerFromUi(enabled: Boolean) {
        val confirmed = ClimateStateOwner.currentState().confirmedState ?: return
        val target = confirmed.driverTargetTemperatureC?.roundToInt() ?: 22
        val fan = if (enabled) (confirmed.fanLevel ?: 0).coerceAtLeast(1) else 0
        submitUi(target, fan, VehicleGatewayContract.ZONE_BOTH, null)
    }

    fun setTemperatureFromUi(zone: Int, target: Int) {
        val confirmed = ClimateStateOwner.currentState().confirmedState ?: return
        val fan = (confirmed.fanLevel ?: 0).coerceAtLeast(1)
        submitUi(target, fan, zone, target)
    }

    fun setFanFromUi(fan: Int) {
        val confirmed = ClimateStateOwner.currentState().confirmedState ?: return
        val target = confirmed.driverTargetTemperatureC?.roundToInt() ?: 22
        submitUi(target, fan, VehicleGatewayContract.ZONE_BOTH, null)
    }

    private fun submitUi(target: Int, fan: Int, zone: Int, requestedTarget: Int?) {
        val requested = ClimateRequestedState(
            driverTargetTemperatureC = requestedTarget?.toFloat(),
            passengerTargetTemperatureC = if (zone == VehicleGatewayContract.ZONE_PASSENGER ||
                zone == VehicleGatewayContract.ZONE_BOTH) requestedTarget?.toFloat() else null,
            fanLevel = fan,
        )
        ClimateStateOwner.markPending(requested, "Waiting for vehicle confirmation")
        val accepted = submit(
            requestId = "ui-${UUID.randomUUID()}",
            targetTemperatureC = target,
            fanLevel = fan,
            zone = zone,
            caller = VehicleGatewayContract.CALLER_DRIVER,
        ) { result ->
            when (result.status) {
                HyperNovaContract.STATUS_ACCEPTED ->
                    ClimateStateOwner.markPending(requested, "Applying climate request")
                HyperNovaContract.STATUS_CONFIRMED ->
                    ClimateStateOwner.finishPending("Climate request confirmed")
                else -> ClimateStateOwner.finishPending(result.message ?: "Climate request failed")
            }
        }
        if (!accepted) ClimateStateOwner.finishPending("Vehicle gateway unavailable")
    }

    private fun publish(state: VehicleState) {
        val connected = state.connectionState == VehicleGatewayContract.CONNECTION_CONNECTED &&
            state.isTelemetryFresh
        val hasData = state.updatedAtEpochMillis > 0
        val availability = when {
            connected -> ClimateAvailability.AVAILABLE
            hasData -> ClimateAvailability.STALE
            else -> ClimateAvailability.UNAVAILABLE
        }
        mutableConnection.value = when (state.connectionState) {
            VehicleGatewayContract.CONNECTION_CONNECTED -> ClimateConnectionState.CONNECTED
            VehicleGatewayContract.CONNECTION_CONNECTING -> ClimateConnectionState.CONNECTING
            else -> ClimateConnectionState.DISCONNECTED
        }
        val dtcs = state.activeDtcs.toSet()
        val health = when {
            !connected -> ClimateHealth.COMMUNICATION_LOST
            VehicleGatewayContract.DTC_P0118 in dtcs -> ClimateHealth.SENSOR_FAILURE
            dtcs.isNotEmpty() -> ClimateHealth.DEGRADED
            else -> ClimateHealth.NORMAL
        }
        val zone1Fan = state.zone1FanLevel.takeIf { it >= 0 }
        val zone2Fan = state.zone2FanLevel.takeIf { it >= 0 }
        val fan = listOfNotNull(zone1Fan, zone2Fan).maxOrNull()
        val powered = (fan ?: 0) > 0
        val previous = ClimateStateOwner.currentState()
        val confirmed = ClimateState(
            availability = availability,
            health = health,
            powerEnabled = powered,
            mode = if (powered) ClimateMode.MANUAL else ClimateMode.OFF,
            driverTargetTemperatureC = state.zone1TargetTemperatureC.takeIf { it >= 0 }?.toFloat(),
            passengerTargetTemperatureC = state.zone2TargetTemperatureC.takeIf { it >= 0 }?.toFloat(),
            cabinTemperatureC = state.cabinTemperatureC.takeIf { it >= 0 }?.toFloat(),
            fanLevel = fan,
            acMode = if (powered) AcMode.COOL else AcMode.OFF,
            autoModeEnabled = false,
            zonesSynchronized = state.zone1TargetTemperatureC >= 0 &&
                state.zone1TargetTemperatureC == state.zone2TargetTemperatureC &&
                state.zone1FanLevel == state.zone2FanLevel,
            updatedAtEpochMillis = state.updatedAtEpochMillis,
        )
        ClimateStateOwner.publishBackendState(
            ClimateUiState(
                capabilities = capabilities,
                confirmedState = confirmed,
                requested = previous.requested,
                isCommandPending = previous.isCommandPending,
                canSendCommands = connected,
                isStale = availability == ClimateAvailability.STALE,
                message = previous.message,
            ),
        )
    }

    private fun disconnected(message: String) {
        synchronized(lock) {
            gateway = null
            binding = false
        }
        mutableConnection.value = ClimateConnectionState.DISCONNECTED
        publishUnavailable(message)
    }

    private fun releaseDeadBinding() {
        val context = synchronized(lock) {
            if (!bound) return
            bound = false
            appContext
        } ?: return
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // The framework may already have removed a dead binding.
        }
    }

    private fun publishUnavailable(message: String) {
        val previous = ClimateStateOwner.currentState().confirmedState
        ClimateStateOwner.publishBackendState(
            ClimateUiState(
                capabilities = capabilities,
                confirmedState = previous?.copy(
                    availability = ClimateAvailability.STALE,
                    health = ClimateHealth.COMMUNICATION_LOST,
                ) ?: ClimateState(
                    availability = ClimateAvailability.UNAVAILABLE,
                    health = ClimateHealth.SERVICE_UNAVAILABLE,
                    powerEnabled = false,
                    mode = ClimateMode.UNAVAILABLE,
                ),
                canSendCommands = false,
                isStale = previous != null,
                message = message,
            ),
        )
    }
}
