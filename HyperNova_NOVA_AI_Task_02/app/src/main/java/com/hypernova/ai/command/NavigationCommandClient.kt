package com.hypernova.ai.command

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.INavigationCommandCallback
import com.hypernova.contracts.navigation.INavigationCommandService
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationResult

class NavigationCommandClient(context: Context) {
    private data class Pending(
        val request: CommandRequest,
        val sink: (CommandResult) -> Unit,
        var callback: INavigationCommandCallback? = null,
    )

    private val appContext = context.applicationContext
    private val lock = Any()
    private val queued = linkedMapOf<String, Pending>()
    private val outstanding = linkedMapOf<String, Pending>()
    private var service: INavigationCommandService? = null
    private var bound = false
    private var binding = false
    private var stopped = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = INavigationCommandService.Stub.asInterface(binder)
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
                failAll("Navigation API version mismatch")
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
            failOutstanding("Navigation service disconnected")
        }

        override fun onBindingDied(name: ComponentName) {
            synchronized(lock) {
                service = null
                binding = false
            }
            safeUnbind()
            failAll("Navigation service binding died")
        }

        override fun onNullBinding(name: ComponentName) {
            synchronized(lock) {
                service = null
                binding = false
            }
            safeUnbind()
            failAll("Navigation service returned no command interface")
        }
    }

    fun execute(request: CommandRequest, sink: (CommandResult) -> Unit) {
        val pending = Pending(request, sink)
        val connected = synchronized(lock) {
            if (stopped) {
                sink(request.unavailable("Navigation client is stopped"))
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
                Intent(NavigationContract.BIND_COMMAND_ACTION).apply {
                    component = ComponentName(
                        NavigationContract.PACKAGE_NAME,
                        NavigationContract.COMMAND_SERVICE,
                    )
                },
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Navigation bind failed", error)
            false
        }
        synchronized(lock) {
            if (started) {
                bound = true
            } else {
                binding = false
            }
        }
        if (!started) failAll("Navigation service is unavailable")
    }

    private fun dispatch(target: INavigationCommandService, pending: Pending) {
        val request = pending.request
        val callback = object : INavigationCommandCallback.Stub() {
            override fun onResult(result: NavigationResult) {
                val mapped = AndroidResultMapper.navigation(request, result)
                if (mapped.status.isFinal) {
                    synchronized(lock) { outstanding.remove(request.requestId) }
                }
                if (
                    mapped.status == CommandStatus.ACCEPTED &&
                    request.operation in
                        setOf(
                            NavigationContract.OP_SET_DESTINATION,
                            NavigationContract.OP_START_NAVIGATION,
                        )
                ) {
                    openNavigation()
                }
                pending.sink(mapped)
            }
        }
        pending.callback = callback
        synchronized(lock) { outstanding[request.requestId] = pending }

        try {
            when (request.operation) {
                NavigationContract.OP_SEARCH_DESTINATIONS -> target.searchDestinations(
                    request.requestId,
                    (request.arguments as CommandArguments.Search).query,
                    callback,
                )
                NavigationContract.OP_GET_SAVED_DESTINATIONS -> target.getSavedDestinations(
                    request.requestId,
                    callback,
                )
                NavigationContract.OP_SET_DESTINATION -> target.setDestination(
                    request.requestId,
                    (request.arguments as CommandArguments.Destination).destinationId,
                    callback,
                )
                NavigationContract.OP_START_NAVIGATION -> target.startNavigation(
                    request.requestId,
                    callback,
                )
                NavigationContract.OP_CANCEL_NAVIGATION -> target.cancelNavigation(
                    request.requestId,
                    callback,
                )
                else -> pending.sink(
                    request.failure(
                        CommandStatus.REJECTED,
                        "Unsupported Navigation operation: ${request.operation}",
                        HyperNovaContract.ERROR_UNSUPPORTED_OPERATION,
                    ),
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "Navigation command failed", error)
            synchronized(lock) { outstanding.remove(request.requestId) }
            pending.sink(request.unavailable("Navigation service is unavailable"))
        }
    }

    private fun openNavigation() {
        try {
            appContext.startActivity(
                Intent(NavigationContract.OPEN_ACTION)
                    .setPackage(NavigationContract.PACKAGE_NAME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (error: Exception) {
            Log.w(TAG, "Could not open Navigation UI", error)
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
        const val TAG = "NavigationCommandClient"
    }
}
