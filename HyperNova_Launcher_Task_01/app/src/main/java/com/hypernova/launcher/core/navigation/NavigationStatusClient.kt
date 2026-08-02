package com.hypernova.launcher.core.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.INavigationCommandCallback
import com.hypernova.contracts.navigation.INavigationCommandService
import com.hypernova.contracts.navigation.INavigationRoutePreviewCallback
import com.hypernova.contracts.navigation.INavigationStatusCallback
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationProgressSnapshot
import com.hypernova.contracts.navigation.NavigationResult
import com.hypernova.contracts.navigation.NavigationRoutePreviewResult
import com.hypernova.contracts.navigation.NavigationRouteSnapshot
import com.hypernova.launcher.core.integration.AppAvailability
import com.hypernova.launcher.core.integration.AppDestination
import com.hypernova.launcher.core.integration.AppLauncher
import com.hypernova.launcher.core.state.AppConnectionState
import java.util.UUID

/**
 * Reads Navigation's current state without issuing a state-changing command.
 *
 * Uses Navigation's read-only current-state operation. The returned destination
 * and route metrics come from Navigation's shared repository/session snapshot.
 */
class NavigationStatusClient(
    context: Context,
    private val appLauncher: AppLauncher,
    private val onSnapshotChanged: (NavigationStatusSnapshot) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: INavigationCommandService? = null
    private var bound = false
    private var started = false
    private var observerRegistered = false
    private var observerSnapshot =
        NavigationStatusSnapshot(AppConnectionState.DISCONNECTED)
    private var pendingRequestId: String? = null
    private var pendingPreviewRequestId: String? = null
    private var pendingPreviewBase: NavigationStatusSnapshot? = null

    private val queryTimeout = Runnable {
        if (pendingRequestId != null) {
            pendingRequestId = null
            publish(
                NavigationStatusSnapshot(
                    connectionState = AppConnectionState.ERROR,
                    errorMessage = "Navigation status request timed out",
                ),
            )
        }
    }

    private val previewTimeout = Runnable {
        if (pendingPreviewRequestId != null) {
            val base = pendingPreviewBase
            clearPendingPreview()
            base?.let(::publish)
        }
    }

    private val callback = object : INavigationCommandCallback.Stub() {
        override fun onResult(result: NavigationResult?) {
            if (result == null || result.requestId != pendingRequestId) return

            mainHandler.post {
                if (result.requestId != pendingRequestId) return@post
                if (result.status != HyperNovaContract.STATUS_ACCEPTED) {
                    pendingRequestId = null
                    mainHandler.removeCallbacks(queryTimeout)
                }
                val snapshot = NavigationStatusMapper.fromResult(result)
                if (
                    result.status == HyperNovaContract.STATUS_CONFIRMED &&
                    snapshot.connectionState == AppConnectionState.READY &&
                    snapshot.runtimeState in PREVIEW_STATES
                ) {
                    requestRoutePreview(snapshot)
                } else {
                    clearPendingPreview()
                    publish(snapshot)
                }
            }
        }
    }

    private val previewCallback = object : INavigationRoutePreviewCallback.Stub() {
        override fun onResult(result: NavigationRoutePreviewResult?) {
            if (result == null || result.requestId != pendingPreviewRequestId) return

            mainHandler.post {
                if (result.requestId != pendingPreviewRequestId) return@post
                val base = pendingPreviewBase
                clearPendingPreview()
                if (base != null) {
                    publish(NavigationStatusMapper.withRoutePreview(base, result))
                }
            }
        }
    }

    private val statusCallback = object : INavigationStatusCallback.Stub() {
        override fun onRouteSnapshot(snapshot: NavigationRouteSnapshot?) {
            if (snapshot == null) return
            mainHandler.post {
                if (!started || !observerRegistered) return@post
                observerSnapshot =
                    NavigationStatusMapper.withRouteSnapshot(
                        observerSnapshot,
                        snapshot,
                    )
                publish(observerSnapshot)
            }
        }

        override fun onProgressSnapshot(snapshot: NavigationProgressSnapshot?) {
            if (snapshot == null) return
            mainHandler.post {
                if (!started || !observerRegistered) return@post
                val merged =
                    NavigationStatusMapper.withProgressSnapshot(
                        observerSnapshot,
                        snapshot,
                    )
                if (merged != observerSnapshot) {
                    observerSnapshot = merged
                    publish(merged)
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connectedService = INavigationCommandService.Stub.asInterface(binder)
            service = connectedService
            try {
                if (connectedService.apiVersion != HyperNovaContract.API_VERSION) {
                    publish(
                        NavigationStatusSnapshot(
                            connectionState = AppConnectionState.ERROR,
                            errorMessage = "Unsupported Navigation status API",
                        ),
                    )
                    return
                }
                observerSnapshot =
                    NavigationStatusSnapshot(AppConnectionState.CONNECTING)
                observerRegistered = tryRegisterStatusObserver(connectedService)
                if (!observerRegistered) {
                    requestCurrentState()
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Could not query Navigation status", exception)
                publishError(exception)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            observerRegistered = false
            service = null
            publishDisconnected()
        }

        override fun onBindingDied(name: ComponentName) {
            releaseBinding()
            publishDisconnected()
            if (started) bind()
        }

        override fun onNullBinding(name: ComponentName) {
            releaseBinding()
            publishDisconnected()
        }
    }

    fun connect() {
        started = true
        if (service != null) {
            if (!observerRegistered) requestCurrentState()
        } else if (!bound) {
            bind()
        }
    }

    fun refresh() {
        if (!started) return
        if (service != null) {
            if (!observerRegistered) requestCurrentState()
        } else if (!bound) {
            bind()
        }
    }

    fun disconnect() {
        started = false
        unregisterStatusObserver()
        pendingRequestId = null
        mainHandler.removeCallbacks(queryTimeout)
        clearPendingPreview()
        releaseBinding()
        service = null
    }

    private fun bind() {
        when (appLauncher.getAvailability(AppDestination.NAVIGATION)) {
            AppAvailability.NOT_INSTALLED -> {
                publish(
                    NavigationStatusSnapshot(AppConnectionState.NOT_INSTALLED),
                )
                return
            }
            AppAvailability.NO_LAUNCHABLE_ACTIVITY,
            AppAvailability.AVAILABLE -> Unit
            AppAvailability.ERROR -> {
                publish(
                    NavigationStatusSnapshot(
                        connectionState = AppConnectionState.ERROR,
                        errorMessage = "Navigation availability unavailable",
                    ),
                )
                return
            }
        }

        val intent = Intent(NavigationContract.BIND_COMMAND_ACTION).apply {
            component = ComponentName(
                NavigationContract.PACKAGE_NAME,
                NavigationContract.COMMAND_SERVICE,
            )
        }

        publish(NavigationStatusSnapshot(AppConnectionState.CONNECTING))
        bound = try {
            applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (exception: Exception) {
            Log.e(TAG, "Could not bind Navigation service", exception)
            publishError(exception)
            false
        }

        if (!bound) publishDisconnected()
    }

    private fun requestCurrentState() {
        val connectedService = service ?: return
        clearPendingPreview()
        val requestId = "launcher-status-${UUID.randomUUID()}"
        pendingRequestId = requestId
        mainHandler.removeCallbacks(queryTimeout)
        mainHandler.postDelayed(queryTimeout, STATUS_TIMEOUT_MS)

        try {
            connectedService.getCurrentNavigationState(requestId, callback)
        } catch (exception: Exception) {
            pendingRequestId = null
            mainHandler.removeCallbacks(queryTimeout)
            Log.e(TAG, "Navigation status request failed", exception)
            publishError(exception)
        }
    }

    /**
     * Register the additive observer. An older service can reject the new
     * transaction; the client then retains the existing one-shot fallback.
     */
    private fun tryRegisterStatusObserver(
        connectedService: INavigationCommandService,
    ): Boolean =
        try {
            connectedService.registerNavigationStatusCallback(statusCallback)
            true
        } catch (exception: Exception) {
            Log.w(TAG, "Navigation observer unavailable; using snapshot fallback", exception)
            false
        }

    private fun unregisterStatusObserver() {
        if (!observerRegistered) return
        try {
            service?.unregisterNavigationStatusCallback(statusCallback)
        } catch (exception: Exception) {
            Log.d(TAG, "Navigation observer was already disconnected", exception)
        } finally {
            observerRegistered = false
        }
    }

    private fun requestRoutePreview(base: NavigationStatusSnapshot) {
        val connectedService = service
        if (connectedService == null) {
            publish(base)
            return
        }
        val requestId = "launcher-preview-${UUID.randomUUID()}"
        pendingPreviewRequestId = requestId
        pendingPreviewBase = base
        mainHandler.removeCallbacks(previewTimeout)
        mainHandler.postDelayed(previewTimeout, STATUS_TIMEOUT_MS)

        try {
            connectedService.getCurrentNavigationRoutePreview(
                requestId,
                previewCallback,
            )
        } catch (exception: Exception) {
            Log.w(TAG, "Navigation route preview request failed", exception)
            clearPendingPreview()
            publish(base)
        }
    }

    private fun publishDisconnected() {
        observerRegistered = false
        observerSnapshot =
            NavigationStatusSnapshot(AppConnectionState.DISCONNECTED)
        pendingRequestId = null
        mainHandler.removeCallbacks(queryTimeout)
        clearPendingPreview()
        publish(NavigationStatusSnapshot(AppConnectionState.DISCONNECTED))
    }

    private fun publishError(exception: Exception) {
        observerRegistered = false
        pendingRequestId = null
        mainHandler.removeCallbacks(queryTimeout)
        clearPendingPreview()
        publish(
            NavigationStatusSnapshot(
                connectionState = AppConnectionState.ERROR,
                errorMessage = exception.message,
            ),
        )
    }

    private fun publish(snapshot: NavigationStatusSnapshot) {
        onSnapshotChanged(snapshot)
    }

    private fun clearPendingPreview() {
        pendingPreviewRequestId = null
        pendingPreviewBase = null
        mainHandler.removeCallbacks(previewTimeout)
    }

    private fun releaseBinding() {
        if (!bound) return
        try {
            applicationContext.unbindService(connection)
        } catch (exception: IllegalArgumentException) {
            Log.d(TAG, "Navigation binding was already released")
        }
        bound = false
    }

    companion object {
        private const val TAG = "NavigationStatusClient"
        private const val STATUS_TIMEOUT_MS = 3_000L
        private val PREVIEW_STATES = setOf(
            NavigationRuntimeState.CALCULATING,
            NavigationRuntimeState.ACTIVE,
            NavigationRuntimeState.ARRIVED,
        )
    }
}
