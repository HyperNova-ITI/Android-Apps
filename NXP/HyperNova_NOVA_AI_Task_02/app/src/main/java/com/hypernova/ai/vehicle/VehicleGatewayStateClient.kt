package com.hypernova.ai.vehicle

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
 * Read-only bridge from the QNX/TC397 gateway AIDL service to NOVA's Pi control connection.
 * Actuation never flows through this class.
 */
class VehicleGatewayStateClient(
    context: Context,
    private val stateSink: (VehicleState) -> Unit,
    private val faultSink: (VehicleFaultEvent) -> Unit,
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var service: IVehicleGatewayService? = null
    private var latestState: VehicleState? = null
    private var bound = false
    private var binding = false
    private var stopped = false

    private val listener = object : IVehicleStateListener.Stub() {
        override fun onVehicleState(state: VehicleState) {
            synchronized(lock) { latestState = state }
            stateSink(state)
        }

        override fun onFault(event: VehicleFaultEvent) {
            faultSink(event)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = IVehicleGatewayService.Stub.asInterface(binder)
            val valid = try {
                connected.getApiVersion() == VehicleGatewayContract.API_VERSION
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
                Log.w(TAG, "Could not register vehicle-state listener", error)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) { service = null }
        }

        override fun onBindingDied(name: ComponentName) {
            synchronized(lock) { service = null }
            safeUnbind()
            start()
        }

        override fun onNullBinding(name: ComponentName) {
            Log.w(TAG, "Vehicle Gateway returned a null binding")
            synchronized(lock) { service = null }
            safeUnbind()
        }
    }

    fun start() {
        val shouldBind = synchronized(lock) {
            if (stopped || bound || binding) false else {
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

    /** Re-publish the latest authoritative snapshot after the Pi socket reconnects. */
    fun publishLatest() {
        synchronized(lock) { latestState }?.let(stateSink)
    }

    fun shutdown() {
        val connected = synchronized(lock) {
            stopped = true
            service.also { service = null }
        }
        try {
            connected?.unregisterVehicleStateListener(listener)
        } catch (_: Exception) {
            // The remote process may already be gone.
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

    private companion object {
        const val TAG = "VehicleGatewayState"
    }
}
