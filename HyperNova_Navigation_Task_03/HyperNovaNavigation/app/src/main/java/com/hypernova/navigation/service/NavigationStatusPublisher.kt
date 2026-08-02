package com.hypernova.navigation.service

import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.hypernova.contracts.navigation.INavigationStatusCallback
import com.hypernova.contracts.navigation.NavigationProgressSnapshot
import com.hypernova.contracts.navigation.NavigationRouteSnapshot
import com.hypernova.navigation.domain.model.NavigationSessionState
import com.hypernova.navigation.domain.repository.NavigationRepository

/** Publishes read-only session snapshots from the repository shared with Navigation UI. */
internal class NavigationStatusPublisher(
    private val repository: NavigationRepository,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val wallClockMillis: () -> Long = System::currentTimeMillis
) {
    private val callbacks = RemoteCallbackList<INavigationStatusCallback>()
    private val planner = NavigationStatusUpdatePlanner()
    private val callbackLock = Any()

    @Volatile
    private var latestRouteSnapshot: NavigationRouteSnapshot

    @Volatile
    private var latestProgressSnapshot: NavigationProgressSnapshot

    private val stateListener: (NavigationSessionState) -> Unit = { state ->
        publish(state)
    }

    init {
        val initial =
            planner.update(
                repository.currentNavigationState(),
                elapsedRealtimeMillis(),
                wallClockMillis(),
                force = true
            )
        latestRouteSnapshot = requireNotNull(initial.routeSnapshot)
        latestProgressSnapshot = requireNotNull(initial.progressSnapshot)
        repository.addNavigationStateListener(stateListener)
    }

    fun register(callback: INavigationStatusCallback?) {
        val receiver = callback ?: return
        if (!callbacks.register(receiver)) return

        deliverRoute(receiver, latestRouteSnapshot)
        deliverProgress(receiver, latestProgressSnapshot)
    }

    fun unregister(callback: INavigationStatusCallback?) {
        callback?.let(callbacks::unregister)
    }

    fun shutdown() {
        repository.removeNavigationStateListener(stateListener)
        callbacks.kill()
    }

    private fun publish(state: NavigationSessionState) {
        val emission =
            planner.update(
                state,
                elapsedRealtimeMillis(),
                wallClockMillis()
            )
        emission.routeSnapshot?.let { snapshot ->
            latestRouteSnapshot = snapshot
            broadcast { callback -> deliverRoute(callback, snapshot) }
        }
        emission.progressSnapshot?.let { snapshot ->
            latestProgressSnapshot = snapshot
            broadcast { callback -> deliverProgress(callback, snapshot) }
        }
    }

    private fun broadcast(delivery: (INavigationStatusCallback) -> Unit) {
        synchronized(callbackLock) {
            val count = callbacks.beginBroadcast()
            try {
                for (index in 0 until count) {
                    delivery(callbacks.getBroadcastItem(index))
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun deliverRoute(
        callback: INavigationStatusCallback,
        snapshot: NavigationRouteSnapshot
    ) {
        try {
            callback.onRouteSnapshot(snapshot)
        } catch (exception: RemoteException) {
            Log.i(TAG, "Navigation status client disconnected")
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Navigation route snapshot callback failed", exception)
        }
    }

    private fun deliverProgress(
        callback: INavigationStatusCallback,
        snapshot: NavigationProgressSnapshot
    ) {
        try {
            callback.onProgressSnapshot(snapshot)
        } catch (exception: RemoteException) {
            Log.i(TAG, "Navigation progress client disconnected")
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Navigation progress callback failed", exception)
        }
    }

    companion object {
        private const val TAG = "HN-NavigationStatus"
    }
}
