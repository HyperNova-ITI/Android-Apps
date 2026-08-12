package com.hypernova.ai.command

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.phone.IPhoneCommandCallback
import com.hypernova.contracts.phone.IPhoneCommandService
import com.hypernova.contracts.phone.PhoneContract
import com.hypernova.contracts.phone.PhoneResult

/** Binds NOVA to the frozen, signature-protected HyperNova Phone AIDL service. */
class PhoneCommandClient(context: Context) {
    private data class Pending(
        val request: CommandRequest,
        val sink: (CommandResult) -> Unit,
        var callback: IPhoneCommandCallback? = null,
    )

    private val appContext = context.applicationContext
    private val lock = Any()
    private val queued = linkedMapOf<String, Pending>()
    private val outstanding = linkedMapOf<String, Pending>()
    private var service: IPhoneCommandService? = null
    private var bound = false
    private var binding = false
    private var stopped = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = IPhoneCommandService.Stub.asInterface(binder)
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
                failAll("Phone API version mismatch")
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
            failOutstanding("Phone service disconnected")
        }

        override fun onBindingDied(name: ComponentName) {
            synchronized(lock) {
                service = null
                binding = false
            }
            safeUnbind()
            failAll("Phone service binding died")
        }

        override fun onNullBinding(name: ComponentName) {
            synchronized(lock) {
                service = null
                binding = false
            }
            safeUnbind()
            failAll("Phone service returned no command interface")
        }
    }

    fun execute(request: CommandRequest, sink: (CommandResult) -> Unit) {
        val pending = Pending(request, sink)
        val connected = synchronized(lock) {
            if (stopped) {
                sink(request.unavailable("Phone client is stopped"))
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
                Intent(PhoneContract.BIND_COMMAND_ACTION).apply {
                    component = ComponentName(PhoneContract.PACKAGE_NAME, PhoneContract.COMMAND_SERVICE)
                },
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Phone bind failed", error)
            false
        }
        synchronized(lock) {
            if (started) bound = true else binding = false
        }
        if (!started) failAll("Phone service is unavailable")
    }

    private fun dispatch(target: IPhoneCommandService, pending: Pending) {
        val request = pending.request
        val callback = object : IPhoneCommandCallback.Stub() {
            override fun onResult(result: PhoneResult) {
                val mapped = AndroidResultMapper.phone(request, result)
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
                PhoneContract.OP_GET_CURRENT_STATE -> target.getCurrentState(request.requestId, callback)
                PhoneContract.OP_SEARCH_CONTACTS -> {
                    val args = request.arguments as CommandArguments.ContactSearch
                    target.searchContacts(request.requestId, args.query, args.limit, callback)
                }
                PhoneContract.OP_GET_CONTACT -> target.getContact(
                    request.requestId,
                    (request.arguments as CommandArguments.Contact).contactId,
                    callback,
                )
                PhoneContract.OP_GET_CALL_HISTORY -> {
                    val args = request.arguments as CommandArguments.CallHistory
                    target.getCallHistory(request.requestId, args.filter, args.limit, callback)
                }
                PhoneContract.OP_GET_CONTACT_CALL_HISTORY -> {
                    val args = request.arguments as CommandArguments.CallHistory
                    target.getCallHistoryForContact(
                        request.requestId,
                        requireNotNull(args.contactId),
                        args.filter,
                        args.limit,
                        callback,
                    )
                }
                PhoneContract.OP_CALL_CONTACT -> {
                    val args = request.arguments as CommandArguments.ContactCall
                    target.callContact(request.requestId, args.contactId, args.numberId, callback)
                }
                PhoneContract.OP_CALL_NUMBER -> target.callNumber(
                    request.requestId,
                    (request.arguments as CommandArguments.PhoneNumber).phoneNumber,
                    callback,
                )
                PhoneContract.OP_CALL_HISTORY_ENTRY -> target.callHistoryEntry(
                    request.requestId,
                    (request.arguments as CommandArguments.CallHistoryEntry).callId,
                    callback,
                )
                PhoneContract.OP_ANSWER_CALL -> target.answerCall(request.requestId, callback)
                PhoneContract.OP_DECLINE_CALL -> target.declineCall(request.requestId, callback)
                PhoneContract.OP_END_CALL -> target.endCall(request.requestId, callback)
                PhoneContract.OP_SET_MUTED -> target.setMuted(
                    request.requestId,
                    (request.arguments as CommandArguments.Enabled).enabled,
                    callback,
                )
                PhoneContract.OP_SET_HELD -> target.setHeld(
                    request.requestId,
                    (request.arguments as CommandArguments.Enabled).enabled,
                    callback,
                )
                PhoneContract.OP_SET_AUDIO_ROUTE -> target.setAudioRoute(
                    request.requestId,
                    (request.arguments as CommandArguments.AudioRoute).route,
                    callback,
                )
                PhoneContract.OP_SEND_DTMF -> target.sendDtmf(
                    request.requestId,
                    (request.arguments as CommandArguments.Dtmf).digit,
                    callback,
                )
                else -> pending.sink(request.failure(
                    CommandStatus.REJECTED,
                    "Unsupported Phone operation: ${request.operation}",
                    HyperNovaContract.ERROR_UNSUPPORTED_OPERATION,
                ))
            }
        } catch (error: Exception) {
            Log.w(TAG, "Phone command failed", error)
            synchronized(lock) { outstanding.remove(request.requestId) }
            pending.sink(request.unavailable("Phone service is unavailable"))
        }
    }

    private fun failOutstanding(message: String) {
        val failed = synchronized(lock) { outstanding.values.toList().also { outstanding.clear() } }
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
        const val TAG = "PhoneCommandClient"
    }
}
