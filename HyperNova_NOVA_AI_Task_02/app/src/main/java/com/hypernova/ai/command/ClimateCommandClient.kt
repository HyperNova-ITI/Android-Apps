package com.hypernova.ai.command

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.climate.ClimateContract
import com.hypernova.contracts.climate.ClimateResult
import com.hypernova.contracts.climate.IClimateCommandCallback
import com.hypernova.contracts.climate.IClimateCommandService

class ClimateCommandClient(context: Context) {
    private data class Pending(
        val request: CommandRequest,
        val sink: (CommandResult) -> Unit,
        var callback: IClimateCommandCallback? = null,
    )

    private val appContext = context.applicationContext
    private val lock = Any()
    private val queued = linkedMapOf<String, Pending>()
    private val outstanding = linkedMapOf<String, Pending>()
    private var service: IClimateCommandService? = null
    private var bound = false
    private var binding = false
    private var stopped = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = IClimateCommandService.Stub.asInterface(binder)
            val valid = try {
                connected.getApiVersion() == HyperNovaContract.API_VERSION
            } catch (_: Exception) {
                false
            }
            if (!valid) {
                synchronized(lock) {
                    service = null
                    binding = false
                }
                safeUnbind()
                failAll("Climate API version mismatch")
                return
            }

            val waiting = synchronized(lock) {
                if (stopped) return
                service = connected
                binding = false
                queued.values.toList().also { queued.clear() }
            }
            waiting.forEach { dispatch(connected, it) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) { service = null }
            failOutstanding("Climate service disconnected")
        }

        override fun onBindingDied(name: ComponentName) {
            synchronized(lock) {
                service = null
                binding = false
            }
            safeUnbind()
            failAll("Climate service binding died")
        }

        override fun onNullBinding(name: ComponentName) {
            synchronized(lock) {
                service = null
                binding = false
            }
            safeUnbind()
            failAll("Climate service returned no command interface")
        }
    }

    fun execute(request: CommandRequest, sink: (CommandResult) -> Unit) {
        val pending = Pending(request, sink)
        val connected = synchronized(lock) {
            if (stopped) {
                sink(request.unavailable("Climate client is stopped"))
                return
            }
            service?.also { return@synchronized it }
            queued[request.requestId] = pending
            null
        }
        if (connected != null) {
            dispatch(connected, pending)
        } else {
            ensureBound()
        }
    }

    fun shutdown() {
        synchronized(lock) {
            stopped = true
            service = null
            queued.clear()
            outstanding.clear()
        }
        safeUnbind()
    }

    private fun ensureBound() {
        val shouldBind = synchronized(lock) {
            if (stopped || bound || binding) false else {
                binding = true
                true
            }
        }
        if (!shouldBind) return

        val started = try {
            appContext.bindService(
                Intent(ClimateContract.BIND_COMMAND_ACTION).apply {
                    component = ComponentName(
                        ClimateContract.PACKAGE_NAME,
                        ClimateContract.COMMAND_SERVICE,
                    )
                },
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Climate bind failed", error)
            false
        }
        synchronized(lock) {
            if (started) {
                bound = true
            } else {
                binding = false
            }
        }
        if (!started) failAll("Climate service is unavailable")
    }

    private fun dispatch(target: IClimateCommandService, pending: Pending) {
        val request = pending.request
        val callback = object : IClimateCommandCallback.Stub() {
            override fun onResult(result: ClimateResult) {
                val mapped = AndroidResultMapper.climate(request, result)
                if (mapped.status.isFinal) {
                    synchronized(lock) { outstanding.remove(request.requestId) }
                }
                pending.sink(mapped)
            }
        }
        pending.callback = callback
        synchronized(lock) { outstanding[request.requestId] = pending }

        try {
            when (request.operation) {
                ClimateContract.OP_GET_CAPABILITIES -> target.getCapabilities(
                    request.requestId,
                    callback,
                )
                ClimateContract.OP_GET_CURRENT_STATE -> target.getCurrentState(
                    request.requestId,
                    callback,
                )
                ClimateContract.OP_SET_POWER -> target.setPowerEnabled(
                    request.requestId,
                    (request.arguments as CommandArguments.Enabled).enabled,
                    callback,
                )
                ClimateContract.OP_SET_TEMPERATURE -> {
                    val args = request.arguments as CommandArguments.Temperature
                    target.setTargetTemperature(
                        request.requestId,
                        args.zone,
                        args.temperatureC,
                        callback,
                    )
                }
                ClimateContract.OP_SET_FAN_LEVEL -> target.setFanLevel(
                    request.requestId,
                    (request.arguments as CommandArguments.FanLevel).level,
                    callback,
                )
                ClimateContract.OP_SET_AC -> target.setAcEnabled(
                    request.requestId,
                    (request.arguments as CommandArguments.Enabled).enabled,
                    callback,
                )
                ClimateContract.OP_SET_AUTO -> target.setAutoModeEnabled(
                    request.requestId,
                    (request.arguments as CommandArguments.Enabled).enabled,
                    callback,
                )
                ClimateContract.OP_SET_RECIRCULATION -> target.setRecirculationEnabled(
                    request.requestId,
                    (request.arguments as CommandArguments.Enabled).enabled,
                    callback,
                )
                else -> pending.sink(
                    request.failure(
                        CommandStatus.REJECTED,
                        "Unsupported Climate operation: ${request.operation}",
                        HyperNovaContract.ERROR_UNSUPPORTED_OPERATION,
                    ),
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "Climate command failed", error)
            synchronized(lock) { outstanding.remove(request.requestId) }
            pending.sink(request.unavailable("Climate service is unavailable"))
        }
    }

    private fun failOutstanding(message: String) {
        val failed = synchronized(lock) {
            outstanding.values.toList().also { outstanding.clear() }
        }
        failed.forEach { it.sink(it.request.unavailable(message)) }
    }

    private fun failAll(message: String) {
        val failed = synchronized(lock) {
            (queued.values + outstanding.values).toList().also {
                queued.clear()
                outstanding.clear()
            }
        }
        failed.forEach { it.sink(it.request.unavailable(message)) }
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

    private fun CommandRequest.unavailable(message: String) = failure(
        status = CommandStatus.UNAVAILABLE,
        message = message,
        errorCode = HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
    )

    private companion object {
        const val TAG = "ClimateCommandClient"
    }
}
