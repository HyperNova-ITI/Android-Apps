package com.hypernova.launcher.core.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.hypernova.contracts.vehiclegateway.IVehicleGatewayService
import com.hypernova.contracts.vehiclegateway.IVehicleStateListener
import com.hypernova.contracts.vehiclegateway.VehicleFaultEvent
import com.hypernova.contracts.vehiclegateway.VehicleGatewayContract
import com.hypernova.contracts.vehiclegateway.VehicleState

/**
 * Read-only bridge from the Vehicle Gateway to the launcher status bar.
 *
 * The cabin temperature in the top bar is the real DHT11 reading from the TC397, carried over the
 * QNX gateway and republished through the signature-protected AIDL service. The launcher never
 * actuates anything: it registers a listener and renders what arrives, so a gateway or TriCore
 * outage degrades to a placeholder instead of a stale number.
 */
class VehicleStatusClient(
    context: Context,
    private val onSnapshotChanged: (VehicleSnapshot) -> Unit,
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var service: IVehicleGatewayService? = null
    private var bound = false
    private var binding = false
    private var stopped = false

    private val listener = object : IVehicleStateListener.Stub() {
        override fun onVehicleState(state: VehicleState) {
            onSnapshotChanged(state.toSnapshot())
        }

        override fun onFault(event: VehicleFaultEvent) {
            // Faults reach the driver through NOVA and the cluster; the status bar shows telemetry.
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = IVehicleGatewayService.Stub.asInterface(binder)
            val valid = try {
                connected.apiVersion == VehicleGatewayContract.API_VERSION
            } catch (_: Exception) {
                false
            }
            if (!valid) {
                Log.w(TAG, "Vehicle Gateway API version mismatch")
                safeUnbind()
                return
            }
            synchronized(lock) {
                if (stopped) return
                service = connected
                binding = false
            }
            try {
                connected.registerVehicleStateListener(listener)
            } catch (error: Exception) {
                Log.w(TAG, "Could not register the vehicle-state listener", error)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) { service = null }
            onSnapshotChanged(VehicleSnapshot())
        }

        override fun onBindingDied(name: ComponentName) {
            synchronized(lock) { service = null }
            onSnapshotChanged(VehicleSnapshot())
            safeUnbind()
            start()
        }

        override fun onNullBinding(name: ComponentName) {
            Log.w(TAG, "Vehicle Gateway returned a null binding")
            synchronized(lock) { service = null }
            safeUnbind()
        }
    }

    /**
     * Bind, or do nothing if already bound. The launcher unbinds in onStop and binds again in
     * onStart, so unlike NOVA's one-shot runtime client this must survive being stopped.
     */
    fun start() {
        val shouldBind = synchronized(lock) {
            stopped = false
            if (bound || binding) false else {
                binding = true
                true
            }
        }
        if (!shouldBind) return
        val started = try {
            appContext.bindService(
                Intent(VehicleGatewayContract.BIND_ACTION).apply {
                    component = ComponentName(
                        VehicleGatewayContract.PACKAGE_NAME,
                        VehicleGatewayContract.COMMAND_SERVICE,
                    )
                },
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Vehicle Gateway bind failed", error)
            false
        }
        synchronized(lock) {
            if (started) bound = true else binding = false
        }
    }

    fun stop() {
        val connected = synchronized(lock) {
            stopped = true
            service.also { service = null }
        }
        try {
            connected?.unregisterVehicleStateListener(listener)
        } catch (_: Exception) {
            // The gateway process may already be gone.
        }
        safeUnbind()
    }

    private fun safeUnbind() {
        val wasBound = synchronized(lock) {
            val value = bound
            bound = false
            binding = false
            value
        }
        if (!wasBound) return
        try {
            appContext.unbindService(connection)
        } catch (_: Exception) {
            // The platform may already have removed a dead binding.
        }
    }

    /**
     * The gateway publishes -1 for every scalar it does not have, including its whole disconnected
     * snapshot, so -1 is the unknown marker rather than a reading of minus one degree.
     */
    private fun VehicleState.toSnapshot(): VehicleSnapshot {
        val connected = connectionState == VehicleGatewayContract.CONNECTION_CONNECTED ||
            connectionState == VehicleGatewayContract.CONNECTION_DEGRADED
        return VehicleSnapshot(
            connected = connected,
            fresh = isTelemetryFresh,
            cabinTemperatureC = cabinTemperatureC.takeIf { it != UNKNOWN },
            humidityPercent = humidityPercent.takeIf { it != UNKNOWN },
            fuelPercent = fuelPercent.takeIf { it != UNKNOWN },
        )
    }

    private companion object {
        const val TAG = "VehicleStatusClient"
        const val UNKNOWN = -1
    }
}
