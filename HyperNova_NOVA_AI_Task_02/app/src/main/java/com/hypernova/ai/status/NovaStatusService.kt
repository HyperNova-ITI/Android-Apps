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
 * Publishes customer-visible state and accepts the three bounded driver controls used by Launcher.
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

    @Volatile
    private var latestSession = NovaRuntimeSnapshot()

    @Volatile
    private var latestMuted = false

    @Volatile
    private var latestDeafened = false

    private val sessionObserver = Observer<NovaRuntimeSnapshot> { snapshot ->
        latestSession = snapshot
        latestState = snapshot.visibleState.name
        publishSnapshot()
    }

    private val mutedObserver = Observer<Boolean> { muted ->
        latestMuted = muted
        publishSnapshot()
    }

    private val deafenedObserver = Observer<Boolean> { deafened ->
        latestDeafened = deafened
        publishSnapshot()
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

        override fun cancelCurrentTurn() = sendRuntimeAction(NovaRuntimeService.ACTION_CANCEL)

        override fun setMuted(muted: Boolean) =
            sendRuntimeAction(NovaRuntimeService.ACTION_SET_MUTED) {
                putExtra(NovaRuntimeService.EXTRA_MUTED, muted)
            }

        override fun setDeafened(deafened: Boolean) =
            sendRuntimeAction(NovaRuntimeService.ACTION_SET_DEAFENED) {
                putExtra(NovaRuntimeService.EXTRA_DEAFENED, deafened)
            }
    }

    override fun onCreate() {
        super.onCreate()
        val current = NovaRuntimeState.session.value ?: NovaRuntimeSnapshot()
        latestSession = current
        latestMuted = NovaRuntimeState.muted.value == true
        latestDeafened = NovaRuntimeState.deafened.value == true
        latestState = current.visibleState.name
        latestSnapshotJson = NovaPresentationSnapshotCodec.encode(
            current,
            muted = latestMuted,
            deafened = latestDeafened,
        )
        NovaRuntimeState.session.observeForever(sessionObserver)
        NovaRuntimeState.muted.observeForever(mutedObserver)
        NovaRuntimeState.deafened.observeForever(deafenedObserver)

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
        NovaRuntimeState.muted.removeObserver(mutedObserver)
        NovaRuntimeState.deafened.removeObserver(deafenedObserver)
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

    private fun publishSnapshot() {
        latestSnapshotJson = NovaPresentationSnapshotCodec.encode(
            latestSession,
            muted = latestMuted,
            deafened = latestDeafened,
        )
        broadcast(latestSnapshotJson)
    }

    private inline fun sendRuntimeAction(action: String, configure: Intent.() -> Unit = {}) {
        val intent = Intent(this, NovaRuntimeService::class.java).apply {
            this.action = action
            configure()
        }
        ContextCompat.startForegroundService(this, intent)
    }

    companion object {
        private const val TAG = "NovaStatusService"
        const val API_VERSION = 3
    }
}
