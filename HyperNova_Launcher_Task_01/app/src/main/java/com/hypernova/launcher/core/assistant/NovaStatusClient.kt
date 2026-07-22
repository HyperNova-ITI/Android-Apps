package com.hypernova.launcher.core.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hypernova.ai.status.INovaStatusCallback
import com.hypernova.ai.status.INovaStatusService

/** Subscribes the launcher widget to NOVA's read-only status contract. */
class NovaStatusClient(
    context: Context,
    private val onSnapshotChanged: (NovaStatusSnapshot) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: INovaStatusService? = null
    private var bound = false
    private var started = false
    private var reconnectDelayMs = RECONNECT_INITIAL_MS

    private val reconnect = Runnable { bindStatusService() }

    private val callback = object : INovaStatusCallback.Stub() {
        override fun onStateChanged(state: String) {
            onSnapshotChanged(
                NovaStatusSnapshot(
                    connection = NovaServiceConnection.CONNECTED,
                    state = state,
                ),
            )
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connectedService = INovaStatusService.Stub.asInterface(binder)
            service = connectedService
            reconnectDelayMs = RECONNECT_INITIAL_MS
            try {
                if (connectedService.apiVersion != SUPPORTED_API_VERSION) {
                    Log.e(TAG, "Unsupported NOVA status API: ${connectedService.apiVersion}")
                    onSnapshotChanged(NovaStatusSnapshot(NovaServiceConnection.ERROR))
                    return
                }
                connectedService.registerCallback(callback)
                onSnapshotChanged(
                    NovaStatusSnapshot(
                        connection = NovaServiceConnection.CONNECTED,
                        state = connectedService.state,
                    ),
                )
            } catch (exception: Exception) {
                Log.e(TAG, "Could not subscribe to NOVA status", exception)
                onSnapshotChanged(NovaStatusSnapshot(NovaServiceConnection.ERROR))
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            onSnapshotChanged(NovaStatusSnapshot(NovaServiceConnection.DISCONNECTED))
        }

        override fun onBindingDied(name: ComponentName) {
            releaseDeadBinding()
            onSnapshotChanged(NovaStatusSnapshot(NovaServiceConnection.ERROR))
            scheduleReconnect()
        }

        override fun onNullBinding(name: ComponentName) {
            releaseDeadBinding()
            onSnapshotChanged(NovaStatusSnapshot(NovaServiceConnection.ERROR))
            scheduleReconnect()
        }
    }

    fun connect() {
        started = true
        reconnectDelayMs = RECONNECT_INITIAL_MS
        mainHandler.removeCallbacks(reconnect)
        bindStatusService()
    }

    private fun bindStatusService() {
        if (!started || bound) return
        onSnapshotChanged(NovaStatusSnapshot(NovaServiceConnection.CONNECTING))

        val intent = Intent(ACTION_BIND_STATUS).apply {
            component = ComponentName(NOVA_PACKAGE, NOVA_STATUS_SERVICE)
        }
        bound = try {
            applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (exception: Exception) {
            Log.e(TAG, "Could not bind NOVA status service", exception)
            false
        }

        if (!bound) {
            onSnapshotChanged(NovaStatusSnapshot(NovaServiceConnection.DISCONNECTED))
            scheduleReconnect()
        }
    }

    fun disconnect() {
        started = false
        mainHandler.removeCallbacks(reconnect)
        if (!bound) return
        try {
            service?.unregisterCallback(callback)
        } catch (exception: Exception) {
            Log.w(TAG, "Could not unregister NOVA status callback", exception)
        }
        applicationContext.unbindService(connection)
        service = null
        bound = false
    }

    private fun releaseDeadBinding() {
        service = null
        if (bound) {
            try {
                applicationContext.unbindService(connection)
            } catch (exception: IllegalArgumentException) {
                Log.d(TAG, "Dead NOVA binding was already released")
            }
        }
        bound = false
    }

    private fun scheduleReconnect() {
        if (!started) return
        mainHandler.removeCallbacks(reconnect)
        mainHandler.postDelayed(reconnect, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
    }

    companion object {
        private const val TAG = "NovaStatusClient"
        private const val ACTION_BIND_STATUS = "com.hypernova.ai.action.BIND_STATUS"
        private const val NOVA_PACKAGE = "com.hypernova.ai"
        private const val NOVA_STATUS_SERVICE = "com.hypernova.ai.status.NovaStatusService"
        private const val SUPPORTED_API_VERSION = 1
        private const val RECONNECT_INITIAL_MS = 1_000L
        private const val RECONNECT_MAX_MS = 5_000L
    }
}
