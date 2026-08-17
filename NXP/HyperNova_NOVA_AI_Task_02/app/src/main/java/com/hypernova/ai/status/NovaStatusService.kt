package com.hypernova.ai.status

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.os.RemoteCallbackList
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.hypernova.ai.runtime.NovaRuntimeService
import com.hypernova.ai.runtime.NovaRuntimeSnapshot
import com.hypernova.ai.runtime.NovaRuntimeState
import com.hypernova.ai.ui.NovaVisibleState

/**
 * Publishes NOVA's customer-visible state to trusted HyperNova system apps.
 *
 * The signature permission on this service keeps the internal contract private
 * while allowing the launcher and NOVA to remain independently deployable APKs.
 */
class NovaStatusService : Service() {
    private val callbacks = RemoteCallbackList<INovaStatusCallback>()

    @Volatile
    private var latestState = NovaVisibleState.UNAVAILABLE.name

    @Volatile
    private var latestSnapshotJson = NovaPresentationSnapshotCodec.encode(NovaRuntimeSnapshot())

    private val sessionObserver = Observer<NovaRuntimeSnapshot> { snapshot ->
        latestState = snapshot.visibleState.name
        latestSnapshotJson = NovaPresentationSnapshotCodec.encode(snapshot)
        broadcast(latestSnapshotJson)
    }

    private val binder = object : INovaStatusService.Stub() {
        override fun getApiVersion(): Int = API_VERSION

        override fun getState(): String = latestState

        override fun getSnapshotJson(): String = latestSnapshotJson

        override fun registerCallback(callback: INovaStatusCallback) {
            callbacks.register(callback)
            try {
                callback.onSnapshotChanged(latestSnapshotJson)
            } catch (exception: RemoteException) {
                callbacks.unregister(callback)
                Log.w(TAG, "Status callback died during registration", exception)
            }
        }

        override fun unregisterCallback(callback: INovaStatusCallback) {
            callbacks.unregister(callback)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val current = NovaRuntimeState.session.value ?: NovaRuntimeSnapshot()
        latestState = current.visibleState.name
        latestSnapshotJson = NovaPresentationSnapshotCodec.encode(current)
        NovaRuntimeState.session.observeForever(sessionObserver)

        // The launcher binding is the product-level signal that NOVA should be available.
        try {
            ContextCompat.startForegroundService(this, Intent(this, NovaRuntimeService::class.java))
        } catch (exception: RuntimeException) {
            // Keep the read-only status contract alive even if this Android image
            // requires a different boot policy for the foreground runtime.
            Log.e(TAG, "Could not start the NOVA runtime", exception)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        NovaRuntimeState.session.removeObserver(sessionObserver)
        callbacks.kill()
        super.onDestroy()
    }

    private fun broadcast(snapshotJson: String) {
        val count = callbacks.beginBroadcast()
        try {
            repeat(count) { index ->
                try {
                    callbacks.getBroadcastItem(index).onSnapshotChanged(snapshotJson)
                } catch (exception: RemoteException) {
                    Log.w(TAG, "Status callback is no longer available", exception)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    companion object {
        private const val TAG = "NovaStatusService"
        const val API_VERSION = 2
    }
}
