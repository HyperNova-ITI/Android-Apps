package com.hypernova.navigation.service

import android.os.RemoteException
import android.util.Log
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.INavigationCommandCallback
import com.hypernova.contracts.navigation.INavigationRoutePreviewCallback
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationResult
import com.hypernova.contracts.navigation.NavigationRoutePreviewResult
import com.hypernova.navigation.NavigationRuntime
import com.hypernova.navigation.model.FailureKind
import com.hypernova.navigation.model.NavigationPhase
import com.hypernova.navigation.model.RoutePreparationResult
import com.hypernova.navigation.persistence.DestinationResolution
import com.hypernova.navigation.persistence.DestinationTokenEntry
import com.hypernova.navigation.places.GooglePlacesException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class NavigationCommandController(
    private val runtime: NavigationRuntime,
    private val scope: CoroutineScope,
    private val requests: RequestRegistry<NavigationResult, PendingCommandCallback> =
        RequestRegistry(HyperNovaContract.REQUEST_DEDUP_TTL_MILLIS),
) {
    fun searchDestinations(
        requestId: String?,
        query: String?,
        callback: INavigationCommandCallback?,
    ) {
        val receiver = callback ?: return
        val id = requestId?.trim().orEmpty()
        val normalized = query?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        val operation = NavigationContract.OP_SEARCH_DESTINATIONS
        if (id.isBlank() || normalized.isBlank()) {
            deliverAsync(receiver, invalid(id, operation, "requestId and query must not be blank."))
            return
        }
        executeDeduplicated(
            key = RequestKey(id, operation),
            fingerprint = normalized,
            callback = receiver,
            acceptedMessage = "Destination search accepted.",
        ) {
            try {
                val values =
                    withTimeout(NavigationContract.SEARCH_TIMEOUT_MILLIS) {
                        runtime.search(normalized)
                    }
                if (values.isEmpty()) {
                    result(
                        id,
                        operation,
                        HyperNovaContract.STATUS_REJECTED,
                        "No destinations found.",
                        NavigationContract.ERROR_NO_RESULTS,
                    )
                } else {
                    result(
                        id,
                        operation,
                        HyperNovaContract.STATUS_CONFIRMED,
                        "Destinations found.",
                        destinations = values.map(ContractProjection::destination),
                    )
                }
            } catch (_: TimeoutCancellationException) {
                timeout(id, operation, "Destination search timed out.")
            } catch (_: GooglePlacesException.ConfigurationRequired) {
                unavailable(id, operation, "Google Maps configuration is required.")
            } catch (failure: GooglePlacesException.RequestFailed) {
                unavailable(
                    id,
                    operation,
                    "Google Places search is unavailable.",
                    NavigationContract.ERROR_OFFLINE_DATA_UNAVAILABLE,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.e(TAG, "Destination search failed", failure)
                unavailable(id, operation, "The navigation service is unavailable.")
            }
        }
    }

    fun getSavedDestinations(requestId: String?, callback: INavigationCommandCallback?) {
        val receiver = callback ?: return
        val id = requestId?.trim().orEmpty()
        val operation = NavigationContract.OP_GET_SAVED_DESTINATIONS
        if (id.isBlank()) {
            deliverAsync(receiver, invalid(id, operation, "requestId must not be blank."))
            return
        }
        executeDeduplicated(
            key = RequestKey(id, operation),
            fingerprint = operation,
            callback = receiver,
            acceptedMessage = "Saved destination lookup accepted.",
        ) {
            val values = runtime.savedDestinations()
            if (values.isEmpty()) {
                result(
                    id,
                    operation,
                    HyperNovaContract.STATUS_REJECTED,
                    "No saved destinations are configured.",
                    NavigationContract.ERROR_NO_SAVED_DESTINATIONS,
                )
            } else {
                result(
                    id,
                    operation,
                    HyperNovaContract.STATUS_CONFIRMED,
                    "Saved destinations found.",
                    destinations = values.map(ContractProjection::destination),
                )
            }
        }
    }

    fun setDestination(
        requestId: String?,
        destinationId: String?,
        callback: INavigationCommandCallback?,
    ) {
        val receiver = callback ?: return
        val id = requestId?.trim().orEmpty()
        val token = destinationId?.trim().orEmpty()
        val operation = NavigationContract.OP_SET_DESTINATION
        if (id.isBlank() || token.isBlank()) {
            deliverAsync(receiver, invalid(id, operation, "requestId and destinationId must not be blank."))
            return
        }
        executeDeduplicated(
            key = RequestKey(id, operation),
            fingerprint = token,
            callback = receiver,
            acceptedMessage = "Route calculation accepted.",
            acceptedState = NavigationContract.STATE_CALCULATING,
        ) {
            when (val resolution = runtime.resolveDestination(token)) {
                DestinationResolution.Expired ->
                    result(
                        id,
                        operation,
                        HyperNovaContract.STATUS_REJECTED,
                        "The destination ID has expired.",
                        NavigationContract.ERROR_DESTINATION_EXPIRED,
                    )
                DestinationResolution.Unknown ->
                    result(
                        id,
                        operation,
                        HyperNovaContract.STATUS_REJECTED,
                        "The destination ID is unknown.",
                        NavigationContract.ERROR_DESTINATION_NOT_FOUND,
                    )
                is DestinationResolution.Found -> prepareRoute(id, operation, resolution.entry)
            }
        }
    }

    fun cancelNavigation(requestId: String?, callback: INavigationCommandCallback?) {
        val receiver = callback ?: return
        val id = requestId?.trim().orEmpty()
        val operation = NavigationContract.OP_CANCEL_NAVIGATION
        if (id.isBlank()) {
            deliverAsync(receiver, invalid(id, operation, "requestId must not be blank."))
            return
        }
        executeDeduplicated(
            key = RequestKey(id, operation),
            fingerprint = operation,
            callback = receiver,
            acceptedMessage = "Navigation cancellation accepted.",
        ) {
            runtime.cancelNavigation()
            result(
                id,
                operation,
                HyperNovaContract.STATUS_CONFIRMED,
                "Navigation is idle.",
                navigationState = NavigationContract.STATE_IDLE,
            )
        }
    }

    fun getCurrentNavigationState(requestId: String?, callback: INavigationCommandCallback?) {
        val receiver = callback ?: return
        val id = requestId?.trim().orEmpty()
        val operation = NavigationContract.OP_GET_CURRENT_STATE
        if (id.isBlank()) {
            deliverAsync(receiver, invalid(id, operation, "requestId must not be blank."))
            return
        }
        scope.launch {
            val state = runtime.state.value
            val metricsAvailable = state.phase in setOf(NavigationPhase.GUIDING, NavigationPhase.REROUTING, NavigationPhase.ARRIVED)
            safeDeliver(
                receiver,
                result(
                    id,
                    operation,
                    HyperNovaContract.STATUS_CONFIRMED,
                    state.statusMessage,
                    selected = ContractProjection.selectedDestination(state),
                    navigationState = ContractProjection.state(state),
                    etaSeconds = if (metricsAvailable) state.etaSeconds else -1L,
                    distanceMeters = if (metricsAvailable) state.distanceMeters else -1L,
                ),
            )
        }
    }

    fun getCurrentNavigationRoutePreview(
        requestId: String?,
        callback: INavigationRoutePreviewCallback?,
    ) {
        val receiver = callback ?: return
        val id = requestId?.trim().orEmpty()
        scope.launch {
            val state = runtime.state.value
            val preview = ContractProjection.preview(state)
            val response =
                if (id.isBlank()) {
                    NavigationRoutePreviewResult(
                        id,
                        HyperNovaContract.STATUS_REJECTED,
                        "requestId must not be blank.",
                        HyperNovaContract.ERROR_INVALID_ARGUMENT,
                        ContractProjection.state(state),
                        preview,
                    )
                } else {
                    NavigationRoutePreviewResult(
                        id,
                        HyperNovaContract.STATUS_CONFIRMED,
                        if (preview.routePoints.isEmpty()) {
                            "Route geometry is available only on the Google Maps surface."
                        } else {
                            "Route preview is available."
                        },
                        HyperNovaContract.ERROR_NONE,
                        ContractProjection.state(state),
                        preview,
                    )
                }
            safeDeliver(receiver, response)
        }
    }

    private fun executeDeduplicated(
        key: RequestKey,
        fingerprint: String,
        callback: INavigationCommandCallback,
        acceptedMessage: String,
        acceptedState: Int = ContractProjection.state(runtime.state.value),
        operation: suspend () -> NavigationResult,
    ) {
        val accepted =
            result(
                key.requestId,
                key.operation,
                HyperNovaContract.STATUS_ACCEPTED,
                acceptedMessage,
                navigationState = acceptedState,
            )
        val pending = PendingCommandCallback(callback)
        when (val registration = requests.begin(key, fingerprint, accepted, pending)) {
            RequestRegistration.New ->
                scope.launch {
                    safeDeliver(callback, accepted)
                    pending.acceptedDelivered.complete(Unit)
                    val final =
                        try {
                            operation()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Exception) {
                            Log.e(TAG, "Navigation command failed: ${key.operation}", failure)
                            unavailable(
                                key.requestId,
                                key.operation,
                                "The navigation service is unavailable.",
                            )
                        }
                    requests.complete(key, final)?.forEach { receiver ->
                        receiver.acceptedDelivered.await()
                        safeDeliver(receiver.callback, final)
                    }
                }
            is RequestRegistration.InFlight ->
                scope.launch {
                    safeDeliver(callback, registration.accepted)
                    pending.acceptedDelivered.complete(Unit)
                }
            is RequestRegistration.Completed -> deliverAsync(callback, registration.result)
            RequestRegistration.Conflict ->
                deliverAsync(
                    callback,
                    invalid(
                        key.requestId,
                        key.operation,
                        "requestId was reused with different arguments.",
                    ),
                )
        }
    }

    private suspend fun prepareRoute(
        requestId: String,
        operation: String,
        entry: DestinationTokenEntry,
    ): NavigationResult =
        try {
            when (
                val preparation =
                    withTimeout(NavigationContract.ROUTE_TIMEOUT_MILLIS) {
                        runtime.prepareDestination(entry)
                    }
            ) {
                is RoutePreparationResult.Ready ->
                    result(
                        requestId,
                        operation,
                        HyperNovaContract.STATUS_CONFIRMED,
                        "Destination set to ${entry.record.title}. Route is ready.",
                        selected = ContractProjection.destination(entry),
                        navigationState = NavigationContract.STATE_IDLE,
                        etaSeconds = preparation.state.etaSeconds,
                        distanceMeters = preparation.state.distanceMeters,
                    )
                is RoutePreparationResult.Failed -> failureResult(requestId, operation, preparation)
            }
        } catch (_: TimeoutCancellationException) {
            runtime.cancelNavigation()
            timeout(requestId, operation, "Route calculation timed out.")
        }

    private fun failureResult(
        requestId: String,
        operation: String,
        failure: RoutePreparationResult.Failed,
    ): NavigationResult {
        val status =
            when (failure.kind) {
                FailureKind.NO_ROUTE -> HyperNovaContract.STATUS_REJECTED
                FailureKind.CANCELLED -> HyperNovaContract.STATUS_CANCELLED
                else -> HyperNovaContract.STATUS_UNAVAILABLE
            }
        val error =
            when (failure.kind) {
                FailureKind.LOCATION -> NavigationContract.ERROR_LOCATION_UNAVAILABLE
                FailureKind.NETWORK -> NavigationContract.ERROR_OFFLINE_DATA_UNAVAILABLE
                FailureKind.NO_ROUTE -> NavigationContract.ERROR_ROUTE_NOT_FOUND
                FailureKind.CANCELLED -> HyperNovaContract.ERROR_NONE
                FailureKind.CONFIGURATION,
                FailureKind.AUTHORIZATION,
                -> HyperNovaContract.ERROR_SERVICE_UNAVAILABLE
                FailureKind.INTERNAL,
                FailureKind.TERMS,
                -> HyperNovaContract.ERROR_INTERNAL
            }
        return result(requestId, operation, status, failure.message, error)
    }

    private fun invalid(requestId: String, operation: String, message: String): NavigationResult =
        result(
            requestId,
            operation,
            HyperNovaContract.STATUS_REJECTED,
            message,
            HyperNovaContract.ERROR_INVALID_ARGUMENT,
        )

    private fun timeout(requestId: String, operation: String, message: String): NavigationResult =
        result(
            requestId,
            operation,
            HyperNovaContract.STATUS_TIMEOUT,
            message,
            HyperNovaContract.ERROR_TIMEOUT,
        )

    private fun unavailable(
        requestId: String,
        operation: String,
        message: String,
        errorCode: String = HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
    ): NavigationResult =
        result(requestId, operation, HyperNovaContract.STATUS_UNAVAILABLE, message, errorCode)

    private fun result(
        requestId: String,
        operation: String,
        status: Int,
        message: String,
        errorCode: String = HyperNovaContract.ERROR_NONE,
        destinations: List<com.hypernova.contracts.navigation.NavigationDestination> = emptyList(),
        selected: com.hypernova.contracts.navigation.NavigationDestination? = null,
        navigationState: Int = ContractProjection.state(runtime.state.value),
        etaSeconds: Long = -1L,
        distanceMeters: Long = -1L,
    ) = NavigationResult(
        requestId,
        operation,
        status,
        message,
        errorCode,
        destinations,
        selected,
        navigationState,
        etaSeconds,
        distanceMeters,
    )

    private fun deliverAsync(callback: INavigationCommandCallback, value: NavigationResult) {
        scope.launch { safeDeliver(callback, value) }
    }

    private fun safeDeliver(callback: INavigationCommandCallback, value: NavigationResult) {
        try {
            callback.onResult(value)
        } catch (_: RemoteException) {
            Log.i(TAG, "Navigation command client disconnected")
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Navigation command callback failed", failure)
        }
    }

    private fun safeDeliver(callback: INavigationRoutePreviewCallback, value: NavigationRoutePreviewResult) {
        try {
            callback.onResult(value)
        } catch (_: RemoteException) {
            Log.i(TAG, "Navigation preview client disconnected")
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Navigation preview callback failed", failure)
        }
    }

    private companion object {
        const val TAG = "HN-GoogleNavAidl"
    }
}

internal class PendingCommandCallback(
    val callback: INavigationCommandCallback,
) {
    val acceptedDelivered = CompletableDeferred<Unit>()

    override fun equals(other: Any?): Boolean =
        other is PendingCommandCallback && callback.asBinder() == other.callback.asBinder()

    override fun hashCode(): Int = callback.asBinder().hashCode()
}
