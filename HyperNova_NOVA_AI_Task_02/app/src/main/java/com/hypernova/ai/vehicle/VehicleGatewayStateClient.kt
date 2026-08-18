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

    /**
     * The last event forwarded for each DTC, so the same fault is only announced once.
     *
     * The gateway re-reports an active fault on every TC397 event batch, and each of those used to
     * become a separate proactive alert on the Pi: NOVA said the same warning over and over and
     * the cockpit printed a new line each time. Only a real change - a fault appearing, clearing,
     * or arriving with a newer TC sequence - is worth telling the Pi about.
     */
    private val forwardedFaults = mutableMapOf<Int, VehicleFaultEvent>()
    private var bound = false
    private var binding = false
    private var stopped = false

    private val listener = object : IVehicleStateListener.Stub() {
        override fun onVehicleState(state: VehicleState) {
            synchronized(lock) { latestState = state }
            stateSink(state)
        }

        override fun onFault(event: VehicleFaultEvent) {
            if (shouldForward(event)) faultSink(event)
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

    /**
     * Re-publish the latest authoritative snapshot after the Pi socket reconnects.
     *
     * Active faults go with it. A restarted Pi has no memory of them, so the de-duplication cache
     * is cleared first: this is the one case where repeating a fault is the correct behaviour.
     */
    fun publishLatest() {
        val (state, faults) = synchronized(lock) {
            val active = forwardedFaults.values.filter { it.isActive }
            forwardedFaults.clear()
            latestState to active
        }
        state?.let(stateSink)
        faults.forEach { event ->
            synchronized(lock) { forwardedFaults[event.dtc] = event }
            faultSink(event)
        }
    }

    /**
     * True when this event says something new about its DTC.
     *
     * A repeat of an already-reported fault is dropped; a fault that becomes active or inactive,
     * or that arrives carrying a newer TC397 event sequence, is always forwarded.
     */
    private fun shouldForward(event: VehicleFaultEvent): Boolean = synchronized(lock) {
        val previous = forwardedFaults[event.dtc]
        val known = previous != null &&
            previous.isActive == event.isActive &&
            previous.tcEventSequence == event.tcEventSequence
        forwardedFaults[event.dtc] = event
        !known
    }

    fun shutdown() {
        val connected = synchronized(lock) {
            stopped = true
            forwardedFaults.clear()
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
