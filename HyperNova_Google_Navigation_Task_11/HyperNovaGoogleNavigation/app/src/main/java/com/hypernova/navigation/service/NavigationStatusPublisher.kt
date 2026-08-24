package com.hypernova.navigation.service

import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.hypernova.contracts.navigation.INavigationStatusCallback
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationProgressSnapshot
import com.hypernova.contracts.navigation.NavigationRouteSnapshot
import com.hypernova.navigation.NavigationRuntime
import com.hypernova.navigation.model.NavigationSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NavigationStatusPublisher(
    runtime: NavigationRuntime,
    scope: CoroutineScope,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val callbacks = RemoteCallbackList<INavigationStatusCallback>()
    private val callbackLock = Any()
    private var latestRoute = ContractProjection.routeSnapshot(runtime.state.value)
    private var latestProgress = ContractProjection.progressSnapshot(runtime.state.value)
    private var lastRouteVersion = Long.MIN_VALUE
    private var lastProgressAt = Long.MIN_VALUE
    private val collector: Job =
        scope.launch {
            runtime.state.collectLatest(::publish)
        }

    fun register(callback: INavigationStatusCallback?) {
        val receiver = callback ?: return
        synchronized(callbackLock) {
            if (!callbacks.register(receiver)) return
            deliverRoute(receiver, latestRoute)
            deliverProgress(receiver, latestProgress)
        }
    }

    fun unregister(callback: INavigationStatusCallback?) {
        callback?.let(callbacks::unregister)
    }

    fun shutdown() {
        collector.cancel()
        callbacks.kill()
    }

    private fun publish(state: NavigationSessionState) {
        val now = elapsedRealtime()
        val routeChanged = state.routeVersion != lastRouteVersion
        if (routeChanged) {
            latestRoute = ContractProjection.routeSnapshot(state)
            lastRouteVersion = state.routeVersion
            broadcast { deliverRoute(it, latestRoute) }
        }
        if (
            routeChanged ||
            lastProgressAt == Long.MIN_VALUE ||
            now - lastProgressAt >= NavigationContract.MIN_PROGRESS_UPDATE_INTERVAL_MILLIS
        ) {
            latestProgress = ContractProjection.progressSnapshot(state)
            lastProgressAt = now
            broadcast { deliverProgress(it, latestProgress) }
        }
    }

    private fun broadcast(delivery: (INavigationStatusCallback) -> Unit) {
        synchronized(callbackLock) {
            val count = callbacks.beginBroadcast()
            try {
                repeat(count) { index -> delivery(callbacks.getBroadcastItem(index)) }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun deliverRoute(callback: INavigationStatusCallback, value: NavigationRouteSnapshot) {
        try {
            callback.onRouteSnapshot(value)
        } catch (_: RemoteException) {
            Log.i(TAG, "Navigation status observer disconnected")
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Navigation route callback failed", failure)
        }
    }

    private fun deliverProgress(callback: INavigationStatusCallback, value: NavigationProgressSnapshot) {
        try {
            callback.onProgressSnapshot(value)
        } catch (_: RemoteException) {
            Log.i(TAG, "Navigation status observer disconnected")
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Navigation progress callback failed", failure)
        }
    }

    private companion object {
        const val TAG = "HN-GoogleNavStatus"
    }
}
