package com.hypernova.navigation.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.INavigationCommandCallback
import com.hypernova.contracts.navigation.INavigationCommandService
import com.hypernova.contracts.navigation.INavigationRoutePreviewCallback
import com.hypernova.contracts.navigation.INavigationStatusCallback
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationDestination
import com.hypernova.contracts.navigation.NavigationProgressSnapshot
import com.hypernova.contracts.navigation.NavigationResult
import com.hypernova.contracts.navigation.NavigationRoutePreview
import com.hypernova.contracts.navigation.NavigationRoutePreviewResult
import com.hypernova.contracts.navigation.NavigationRouteSnapshot
import com.hypernova.navigation.MockMode
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class NavigationCommandService : Service() {
    private data class Cached(val result: NavigationResult, val storedAt: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, Cached>()
    private val knownDestinations = ConcurrentHashMap<String, NavigationDestination>()
    @Volatile private var activeDestination: NavigationDestination? = null

    private val home = NavigationDestination(
        "saved:home",
        NavigationContract.SOURCE_SAVED_HOME,
        "Home",
        "Driver profile home",
        "Saved",
        8_200,
    )
    private val work = NavigationDestination(
        "saved:work",
        NavigationContract.SOURCE_SAVED_WORK,
        "Work",
        "Driver profile work",
        "Saved",
        12_600,
    )

    override fun onCreate() {
        super.onCreate()
        knownDestinations[home.id] = home
        knownDestinations[work.id] = work
    }

    private val binder = object : INavigationCommandService.Stub() {
        override fun getApiVersion(): Int = HyperNovaContract.API_VERSION

        override fun searchDestinations(
            requestId: String,
            query: String,
            callback: INavigationCommandCallback,
        ) {
            if (replay(requestId, callback)) return
            if (query.isBlank()) {
                finish(callback, rejected(
                    requestId,
                    NavigationContract.OP_SEARCH_DESTINATIONS,
                    "Tell me what destination to search for",
                    HyperNovaContract.ERROR_INVALID_ARGUMENT,
                ))
                return
            }
            if (applyFailureMode(requestId, NavigationContract.OP_SEARCH_DESTINATIONS, callback)) return

            accept(requestId, NavigationContract.OP_SEARCH_DESTINATIONS, callback, "Searching destinations")
            handler.postDelayed({
                val slug = query.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                val results = listOf(
                    destination("search:$slug:1", "$query Central", "1.8 km away", 1_800),
                    destination("search:$slug:2", "$query Riverside", "3.1 km away", 3_100),
                    destination("search:$slug:3", "$query Plaza", "4.6 km away", 4_600),
                    destination("search:$slug:4", "$query District", "6.2 km away", 6_200),
                )
                results.forEach { knownDestinations[it.id] = it }
                finish(callback, NavigationResult(
                    requestId,
                    NavigationContract.OP_SEARCH_DESTINATIONS,
                    HyperNovaContract.STATUS_CONFIRMED,
                    "I found four destinations",
                    HyperNovaContract.ERROR_NONE,
                    results,
                    null,
                    NavigationContract.STATE_IDLE,
                    -1,
                    -1,
                ))
                MockMode.status(this@NavigationCommandService, "Returned four results for “$query”")
            }, RESPONSE_DELAY_MILLIS)
        }

        override fun getSavedDestinations(
            requestId: String,
            callback: INavigationCommandCallback,
        ) {
            if (replay(requestId, callback)) return
            if (applyFailureMode(requestId, NavigationContract.OP_GET_SAVED_DESTINATIONS, callback)) return
            finish(callback, NavigationResult(
                requestId,
                NavigationContract.OP_GET_SAVED_DESTINATIONS,
                HyperNovaContract.STATUS_CONFIRMED,
                "Saved destinations are Home and Work",
                HyperNovaContract.ERROR_NONE,
                listOf(home, work),
                null,
                NavigationContract.STATE_IDLE,
                -1,
                -1,
            ))
            MockMode.status(this@NavigationCommandService, "Returned Home and Work")
        }

        override fun setDestination(
            requestId: String,
            destinationId: String,
            callback: INavigationCommandCallback,
        ) {
            if (replay(requestId, callback)) return
            if (applyFailureMode(requestId, NavigationContract.OP_SET_DESTINATION, callback)) return
            val destination = knownDestinations[destinationId]
            if (destination == null) {
                finish(callback, rejected(
                    requestId,
                    NavigationContract.OP_SET_DESTINATION,
                    "That destination is no longer available",
                    NavigationContract.ERROR_DESTINATION_EXPIRED,
                ))
                return
            }

            accept(requestId, NavigationContract.OP_SET_DESTINATION, callback, "Calculating route")
            MockMode.status(this@NavigationCommandService, "Calculating route to ${destination.title}")
            handler.postDelayed({
                activeDestination = destination
                finish(callback, NavigationResult(
                    requestId,
                    NavigationContract.OP_SET_DESTINATION,
                    HyperNovaContract.STATUS_CONFIRMED,
                    "Route to ${destination.title} started",
                    HyperNovaContract.ERROR_NONE,
                    emptyList(),
                    destination,
                    NavigationContract.STATE_ACTIVE,
                    900,
                    destination.distanceMeters,
                ))
                MockMode.status(this@NavigationCommandService, "Guidance active: ${destination.title}")
            }, ROUTE_DELAY_MILLIS)
        }

        override fun cancelNavigation(
            requestId: String,
            callback: INavigationCommandCallback,
        ) {
            if (replay(requestId, callback)) return
            if (applyFailureMode(requestId, NavigationContract.OP_CANCEL_NAVIGATION, callback)) return
            activeDestination = null
            finish(callback, NavigationResult(
                requestId,
                NavigationContract.OP_CANCEL_NAVIGATION,
                HyperNovaContract.STATUS_CONFIRMED,
                "Navigation cancelled",
                HyperNovaContract.ERROR_NONE,
                emptyList(),
                null,
                NavigationContract.STATE_IDLE,
                -1,
                -1,
            ))
            MockMode.status(this@NavigationCommandService, "Navigation idle")
        }

        override fun getCurrentNavigationState(
            requestId: String,
            callback: INavigationCommandCallback,
        ) {
            val destination = activeDestination
            val state = if (destination == null) {
                NavigationContract.STATE_IDLE
            } else {
                NavigationContract.STATE_ACTIVE
            }
            finish(callback, NavigationResult(
                requestId,
                NavigationContract.OP_GET_CURRENT_STATE,
                HyperNovaContract.STATUS_CONFIRMED,
                if (destination == null) "Navigation is idle" else "Guidance is active",
                HyperNovaContract.ERROR_NONE,
                emptyList(),
                destination,
                state,
                if (destination == null) -1 else 900,
                destination?.distanceMeters ?: -1,
            ))
        }

        override fun getCurrentNavigationRoutePreview(
            requestId: String,
            callback: INavigationRoutePreviewCallback,
        ) {
            val destination = activeDestination
            try {
                callback.onResult(NavigationRoutePreviewResult(
                    requestId,
                    HyperNovaContract.STATUS_CONFIRMED,
                    if (destination == null) "No active route" else "Route preview available",
                    HyperNovaContract.ERROR_NONE,
                    if (destination == null) NavigationContract.STATE_IDLE else NavigationContract.STATE_ACTIVE,
                    NavigationRoutePreview.empty(),
                ))
            } catch (_: Exception) {
                // The caller owns reconnection and timeout recovery.
            }
        }

        override fun registerNavigationStatusCallback(callback: INavigationStatusCallback) {
            val destination = activeDestination
            val state = if (destination == null) {
                NavigationContract.STATE_IDLE
            } else {
                NavigationContract.STATE_ACTIVE
            }
            try {
                callback.onRouteSnapshot(NavigationRouteSnapshot(
                    destination?.id ?: "",
                    if (destination == null) 0 else 1,
                    state,
                    destination,
                    if (destination == null) -1 else 900,
                    destination?.distanceMeters ?: -1,
                    NavigationRoutePreview.empty(),
                ))
                callback.onProgressSnapshot(NavigationProgressSnapshot(
                    destination?.id ?: "",
                    if (destination == null) 0 else 1,
                    state,
                    0,
                    null,
                    destination?.distanceMeters ?: -1,
                ))
            } catch (_: Exception) {
                // The caller owns reconnection and timeout recovery.
            }
        }

        override fun unregisterNavigationStatusCallback(callback: INavigationStatusCallback) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? =
        binder.takeIf { intent?.action == NavigationContract.BIND_COMMAND_ACTION }

    private fun destination(id: String, title: String, subtitle: String, distance: Long) =
        NavigationDestination(
            id,
            NavigationContract.SOURCE_SEARCH,
            title,
            subtitle,
            "Demo search result",
            distance,
        )

    private fun applyFailureMode(
        requestId: String,
        operation: String,
        callback: INavigationCommandCallback,
    ): Boolean = when (MockMode.get(this)) {
        MockMode.REJECT -> {
            finish(callback, rejected(
                requestId,
                operation,
                "Navigation rejected the demo request",
                NavigationContract.ERROR_ROUTE_NOT_FOUND,
            ))
            true
        }
        MockMode.UNAVAILABLE -> {
            finish(callback, NavigationResult(
                requestId,
                operation,
                HyperNovaContract.STATUS_UNAVAILABLE,
                "Navigation location service is unavailable",
                NavigationContract.ERROR_LOCATION_UNAVAILABLE,
                emptyList(),
                null,
                NavigationContract.STATE_ERROR,
                -1,
                -1,
            ))
            true
        }
        MockMode.TIMEOUT -> {
            accept(requestId, operation, callback, "Navigation request accepted")
            MockMode.status(this, "Holding request to demonstrate NOVA timeout")
            true
        }
        else -> false
    }

    private fun accept(
        requestId: String,
        operation: String,
        callback: INavigationCommandCallback,
        message: String,
    ) = finish(callback, NavigationResult(
        requestId,
        operation,
        HyperNovaContract.STATUS_ACCEPTED,
        message,
        HyperNovaContract.ERROR_NONE,
        emptyList(),
        null,
        NavigationContract.STATE_CALCULATING,
        -1,
        -1,
    ))

    private fun rejected(
        requestId: String,
        operation: String,
        message: String,
        error: String,
    ) = NavigationResult(
        requestId,
        operation,
        HyperNovaContract.STATUS_REJECTED,
        message,
        error,
        emptyList(),
        null,
        NavigationContract.STATE_ERROR,
        -1,
        -1,
    )

    private fun finish(callback: INavigationCommandCallback, result: NavigationResult) {
        pruneCache()
        cache[result.requestId] = Cached(result, System.currentTimeMillis())
        try {
            callback.onResult(result)
        } catch (_: Exception) {
            // The caller owns reconnection and timeout recovery.
        }
    }

    private fun replay(requestId: String, callback: INavigationCommandCallback): Boolean {
        pruneCache()
        val cached = cache[requestId]?.result ?: return false
        try {
            callback.onResult(cached)
        } catch (_: Exception) {
            // The caller may have disconnected after retrying.
        }
        return true
    }

    private fun pruneCache() {
        val cutoff = System.currentTimeMillis() - HyperNovaContract.REQUEST_DEDUP_TTL_MILLIS
        cache.entries.removeAll { it.value.storedAt < cutoff }
    }

    private companion object {
        const val RESPONSE_DELAY_MILLIS = 250L
        const val ROUTE_DELAY_MILLIS = 600L
    }
}
