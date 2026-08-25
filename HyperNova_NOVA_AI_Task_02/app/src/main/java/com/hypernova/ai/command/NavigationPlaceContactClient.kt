package com.hypernova.ai.command

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.INavigationPlaceContactCallback
import com.hypernova.contracts.navigation.INavigationPlaceContactService
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationPlaceContactResult

/** Client for Navigation's additive read-only Google Place contact service. */
class NavigationPlaceContactClient(context: Context) {
    private data class Pending(
        val request: CommandRequest,
        val sink: (CommandResult) -> Unit,
        var callback: INavigationPlaceContactCallback? = null,
    )

    private val appContext = context.applicationContext
    private val lock = Any()
    private val queued = linkedMapOf<String, Pending>()
    private val outstanding = linkedMapOf<String, Pending>()
    private var service: INavigationPlaceContactService? = null
    private var bound = false
    private var binding = false
    private var stopped = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = INavigationPlaceContactService.Stub.asInterface(binder)
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
                failAll("Navigation place-contact API version mismatch")
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
            failOutstanding("Navigation place-contact service disconnected")
        }

        override fun onBindingDied(name: ComponentName) {
            synchronized(lock) {
                service = null
                binding = false
            }
            safeUnbind()
            failAll("Navigation place-contact service binding died")
        }

        override fun onNullBinding(name: ComponentName) {
            synchronized(lock) {
                service = null
                binding = false
            }
            safeUnbind()
            failAll("Navigation place-contact service returned no interface")
        }
    }

    fun execute(request: CommandRequest, sink: (CommandResult) -> Unit) {
        val pending = Pending(request, sink)
        val connected = synchronized(lock) {
            if (stopped) {
                sink(request.unavailable("Navigation place-contact client is stopped"))
                return
            }
            service?.also { return@synchronized it }
            queued[request.requestId] = pending
            null
        }
        if (connected != null) dispatch(connected, pending) else ensureBound()
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
                Intent(NavigationContract.BIND_PLACE_CONTACT_ACTION).apply {
                    component = ComponentName(
                        NavigationContract.PACKAGE_NAME,
                        NavigationContract.PLACE_CONTACT_SERVICE,
                    )
                },
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Navigation place-contact bind failed", error)
            false
        }
        synchronized(lock) {
            if (started) bound = true else binding = false
        }
        if (!started) failAll("Place calling is unavailable in HyperNova Navigation")
    }

    private fun dispatch(target: INavigationPlaceContactService, pending: Pending) {
        val request = pending.request
        val destination = request.arguments as? CommandArguments.Destination
        if (destination == null) {
            pending.sink(request.failure(
                CommandStatus.REJECTED,
                "A destination is required for place calling",
                HyperNovaContract.ERROR_INVALID_ARGUMENT,
            ))
            return
        }
        val callback = object : INavigationPlaceContactCallback.Stub() {
            override fun onResult(result: NavigationPlaceContactResult?) {
                synchronized(lock) { outstanding.remove(request.requestId) }
                pending.sink(AndroidResultMapper.navigationPlaceContact(request, result))
            }
        }
        pending.callback = callback
        synchronized(lock) { outstanding[request.requestId] = pending }
        try {
            target.getDestinationContact(
                request.requestId,
                destination.destinationId,
                callback,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Navigation place-contact command failed", error)
            synchronized(lock) { outstanding.remove(request.requestId) }
            pending.sink(request.unavailable("Navigation place-contact service is unavailable"))
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
            // Android may already have removed the dead binding.
        }
    }

    private fun CommandRequest.unavailable(message: String) = failure(
        status = CommandStatus.UNAVAILABLE,
        message = message,
        errorCode = HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
    )

    private companion object {
        const val TAG = "NavigationPlaceContact"
    }
}
