package com.hypernova.launcher.core.climate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.climate.ClimateContract
import com.hypernova.contracts.climate.ClimateResult
import com.hypernova.contracts.climate.ClimateState
import com.hypernova.contracts.climate.IClimateCommandCallback
import com.hypernova.contracts.climate.IClimateCommandService
import com.hypernova.launcher.core.integration.AppAvailability
import com.hypernova.launcher.core.integration.AppDestination
import com.hypernova.launcher.core.integration.AppLauncher
import com.hypernova.launcher.core.state.AppConnectionState
import java.util.UUID

/**
 * Reads Climate state only through the existing read-only AIDL request.
 *
 * Direct vehicle or gateway access is intentionally excluded. If Climate does
 * not expose its contract service, package availability and launch behavior
 * remain available while detailed HVAC values remain unknown.
 */
class ClimateStatusClient(
    context: Context,
    private val appLauncher: AppLauncher,
    private val onSnapshotChanged: (ClimateSnapshot) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false
    private var contractService: IClimateCommandService? = null
    private var contractBound = false

    @Volatile
    private var pendingRequestId: String? = null

    private val contractTimeout = Runnable {
        if (pendingRequestId != null) {
            pendingRequestId = null
            publishServiceUnavailable("Climate status service timed out")
        }
    }

    private val contractCallback = object : IClimateCommandCallback.Stub() {
        override fun onResult(result: ClimateResult?) {
            if (result == null || result.requestId != pendingRequestId) return
            mainHandler.post {
                if (result.requestId != pendingRequestId) return@post
                if (result.status == HyperNovaContract.STATUS_ACCEPTED) return@post

                pendingRequestId = null
                mainHandler.removeCallbacks(contractTimeout)
                val state = result.confirmedState
                if (state != null) {
                    publish(state.toSnapshot())
                } else {
                    publishServiceUnavailable(result.message)
                }
            }
        }
    }

    private val contractConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = IClimateCommandService.Stub.asInterface(binder)
            contractService = service
            try {
                if (service.apiVersion != HyperNovaContract.API_VERSION) {
                    publishServiceUnavailable("Unsupported Climate status API")
                } else {
                    requestContractState()
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Could not query Climate service", exception)
                publishServiceUnavailable(exception.message)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            contractService = null
            if (started) publishServiceUnavailable("Climate service disconnected")
        }

        override fun onBindingDied(name: ComponentName) {
            releaseContractBinding()
            if (started) publishServiceUnavailable("Climate service binding died")
        }

        override fun onNullBinding(name: ComponentName) {
            releaseContractBinding()
            if (started) publishServiceUnavailable("Climate service returned no interface")
        }
    }

    fun connect() {
        if (started) {
            refresh()
            return
        }
        started = true

        when (appLauncher.getAvailability(AppDestination.CLIMATE)) {
            AppAvailability.NOT_INSTALLED -> {
                publish(ClimateSnapshot(AppConnectionState.NOT_INSTALLED))
                return
            }
            AppAvailability.NO_LAUNCHABLE_ACTIVITY,
            AppAvailability.AVAILABLE -> Unit
            AppAvailability.ERROR -> {
                publish(
                    ClimateSnapshot(
                        connectionState = AppConnectionState.ERROR,
                        errorMessage = "Climate availability unavailable",
                    ),
                )
                return
            }
        }

        if (hasContractService()) {
            publish(ClimateSnapshot(AppConnectionState.CONNECTING))
            bindContractService()
        } else {
            publishServiceUnavailable()
        }
    }

    fun refresh() {
        if (!started) return
        when {
            contractService != null -> requestContractState()
            contractBound -> Unit
            hasContractService() -> bindContractService()
            else -> publishServiceUnavailable()
        }
    }

    fun disconnect() {
        started = false
        pendingRequestId = null
        mainHandler.removeCallbacks(contractTimeout)
        contractService = null
        releaseContractBinding()
    }

    private fun hasContractService(): Boolean {
        return try {
            applicationContext.packageManager.getServiceInfo(
                ComponentName(ClimateContract.PACKAGE_NAME, ClimateContract.COMMAND_SERVICE),
                PackageManager.ComponentInfoFlags.of(0L),
            )
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (exception: Exception) {
            Log.w(TAG, "Could not inspect Climate service", exception)
            false
        }
    }

    private fun bindContractService() {
        if (!started || contractBound) return
        val intent = Intent(ClimateContract.BIND_COMMAND_ACTION).apply {
            component = ComponentName(ClimateContract.PACKAGE_NAME, ClimateContract.COMMAND_SERVICE)
        }
        contractBound = try {
            applicationContext.bindService(intent, contractConnection, Context.BIND_AUTO_CREATE)
        } catch (exception: Exception) {
            Log.e(TAG, "Could not bind Climate service", exception)
            false
        }
        if (!contractBound) publishServiceUnavailable("Climate status service unavailable")
    }

    private fun requestContractState() {
        val service = contractService ?: return
        val requestId = "launcher-climate-${UUID.randomUUID()}"
        pendingRequestId = requestId
        mainHandler.removeCallbacks(contractTimeout)
        mainHandler.postDelayed(contractTimeout, ClimateContract.QUERY_TIMEOUT_MILLIS)
        try {
            service.getCurrentState(requestId, contractCallback)
        } catch (exception: Exception) {
            pendingRequestId = null
            mainHandler.removeCallbacks(contractTimeout)
            publishServiceUnavailable(exception.message)
        }
    }

    private fun ClimateState.toSnapshot(): ClimateSnapshot {
        val mappedAvailability = when (availability) {
            ClimateContract.AVAILABILITY_AVAILABLE -> ClimateAvailability.AVAILABLE
            ClimateContract.AVAILABILITY_STALE -> ClimateAvailability.STALE
            else -> ClimateAvailability.UNAVAILABLE
        }
        return ClimateSnapshot(
            connectionState = AppConnectionState.READY,
            availability = mappedAvailability,
            source = ClimateStateSource.CONTRACT,
            powerEnabled = isPowerEnabled(),
            driverTargetTemperatureC = driverTargetTemperatureC.takeUnless(Float::isNaN),
            fanLevel = fanLevel.takeIf { it >= 0 },
            acEnabled = isAcEnabled(),
            autoModeEnabled = isAutoModeEnabled(),
        )
    }

    private fun publishServiceUnavailable(message: String? = null) {
        publish(
            ClimateSnapshot(
                connectionState = AppConnectionState.DISCONNECTED,
                errorMessage = message?.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun releaseContractBinding() {
        if (!contractBound) return
        try {
            applicationContext.unbindService(contractConnection)
        } catch (_: IllegalArgumentException) {
            // The system already released this binding.
        }
        contractBound = false
    }

    private fun publish(snapshot: ClimateSnapshot) {
        onSnapshotChanged(snapshot)
    }

    companion object {
        private const val TAG = "ClimateStatusClient"
    }
}
