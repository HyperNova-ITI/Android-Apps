package com.hypernova.navigation.service

import android.os.RemoteException
import android.util.Log
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.INavigationCommandCallback
import com.hypernova.contracts.navigation.INavigationRoutePreviewCallback
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationResult
import com.hypernova.contracts.navigation.NavigationRoutePreviewResult
import com.hypernova.navigation.domain.model.DestinationResolution
import com.hypernova.navigation.domain.model.NavigationSessionStatus
import com.hypernova.navigation.domain.model.ResolvedDestination
import com.hypernova.navigation.domain.repository.DestinationSearchPolicy
import com.hypernova.navigation.domain.repository.NavigationRepository
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class NavigationCommandController(
    private val repository: NavigationRepository,
    private val executor: ScheduledExecutorService =
        Executors.newScheduledThreadPool(2),
    private val requests:
        RequestRegistry<
            NavigationResult,
            INavigationCommandCallback
        > =
        RequestRegistry(
            HyperNovaContract.REQUEST_DEDUP_TTL_MILLIS
        )
) {
    private val resultFactory =
        NavigationResultFactory(
            repository::currentNavigationState
        )

    fun searchDestinations(
        requestId: String?,
        query: String?,
        callback: INavigationCommandCallback?
    ) {
        val receiver = callback ?: return
        val id = requestId?.trim().orEmpty()
        val normalizedQuery =
            DestinationSearchPolicy.normalizedQuery(query)
                .orEmpty()

        if (id.isBlank() || normalizedQuery.isBlank()) {
            executeDelivery(
                receiver,
                resultFactory.invalidArgument(
                    requestId = id,
                    operation =
                        NavigationContract
                            .OP_SEARCH_DESTINATIONS,
                    message =
                        "requestId and query must not be blank."
                )
            )
            return
        }

        executor.execute {
            val key =
                RequestKey(
                    id,
                    NavigationContract.OP_SEARCH_DESTINATIONS
                )
            val accepted =
                resultFactory.accepted(
                    requestId = id,
                    operation = key.operation,
                    message = "Destination search accepted.",
                    navigationState =
                        resultFactory.currentContractState()
                )

            when (
                begin(
                    key = key,
                    fingerprint = normalizedQuery,
                    accepted = accepted,
                    callback = receiver
                )
            ) {
                BeginDecision.NEW -> {
                    repository.searchDestinations(
                        normalizedQuery
                    ) { result ->
                        result.fold(
                            onSuccess = { destinations ->
                                finish(
                                    key,
                                    resultFactory.searchResult(
                                        requestId = id,
                                        destinations =
                                            destinations
                                    )
                                )
                            },
                            onFailure = { failure ->
                                finish(
                                    key,
                                    resultFactory.providerFailure(
                                        requestId = id,
                                        operation = key.operation,
                                        failure = failure
                                    )
                                )
                            }
                        )
                    }
                    scheduleTimeout(
                        key = key,
                        timeoutMillis =
                            CommandTimeoutPolicy
                                .timeoutMillis(key.operation)
                                ?: return@execute,
                        timeoutResult = {
                            resultFactory.timeout(
                                requestId = id,
                                operation = key.operation,
                                message =
                                    "Destination search timed out."
                            )
                        }
                    )
                }
                BeginDecision.HANDLED -> Unit
            }
        }
    }

    fun getSavedDestinations(
        requestId: String?,
        callback: INavigationCommandCallback?
    ) {
        val receiver = callback ?: return
        val id = requestId?.trim().orEmpty()
        if (id.isBlank()) {
            executeDelivery(
                receiver,
                resultFactory.invalidArgument(
                    requestId = id,
                    operation =
                        NavigationContract
                            .OP_GET_SAVED_DESTINATIONS,
                    message = "requestId must not be blank."
                )
            )
            return
        }

        executor.execute {
            val key =
                RequestKey(
                    id,
                    NavigationContract.OP_GET_SAVED_DESTINATIONS
                )
            val accepted =
                resultFactory.accepted(
                    requestId = id,
                    operation = key.operation,
                    message =
                        "Saved destination lookup accepted.",
                    navigationState =
                        resultFactory.currentContractState()
                )

            when (
                begin(
                    key = key,
                    fingerprint = key.operation,
                    accepted = accepted,
                    callback = receiver
                )
            ) {
                BeginDecision.NEW -> {
                    val destinations =
                        runCatching {
                            repository.getSavedDestinations()
                        }
                    destinations.fold(
                        onSuccess = {
                            finish(
                                key,
                                resultFactory.savedResult(
                                    id,
                                    it
                                )
                            )
                        },
                        onFailure = {
                            finish(
                                key,
                                resultFactory.internalFailure(
                                    id,
                                    key.operation,
                                    it
                                )
                            )
                        }
                    )
                }
                BeginDecision.HANDLED -> Unit
            }
        }
    }

    /** Return the repository's current session without changing it. */
    fun getCurrentNavigationState(
        requestId: String?,
        callback: INavigationCommandCallback?
    ) {
        val receiver = callback ?: return
        val id = requestId?.trim().orEmpty()
        if (id.isBlank()) {
            executeDelivery(
                receiver,
                resultFactory.invalidArgument(
                    requestId = id,
                    operation = NavigationContract.OP_GET_CURRENT_STATE,
                    message = "requestId must not be blank."
                )
            )
            return
        }

        executor.execute {
            val result =
                runCatching {
                    resultFactory.currentStateResult(id)
                }.getOrElse {
                    resultFactory.internalFailure(
                        requestId = id,
                        operation = NavigationContract.OP_GET_CURRENT_STATE,
                        failure = it
                    )
                }
            safeDeliver(receiver, result)
        }
    }

    /** Return bounded selected-route geometry without mutating the session. */
    fun getCurrentNavigationRoutePreview(
        requestId: String?,
        callback: INavigationRoutePreviewCallback?
    ) {
        val receiver = callback ?: return
        val id = requestId?.trim().orEmpty()
        if (id.isBlank()) {
            executeRoutePreviewDelivery(
                receiver,
                resultFactory.invalidRoutePreviewArgument(id)
            )
            return
        }

        executor.execute {
            val result =
                runCatching {
                    resultFactory.currentRoutePreviewResult(id)
                }.getOrElse {
                    resultFactory.routePreviewFailure(id, it)
                }
            safeDeliverRoutePreview(receiver, result)
        }
    }

    fun setDestination(
        requestId: String?,
        destinationId: String?,
        callback: INavigationCommandCallback?
    ) {
        val receiver = callback ?: return
        val id = requestId?.trim().orEmpty()
        val token = destinationId?.trim().orEmpty()

        if (id.isBlank() || token.isBlank()) {
            executeDelivery(
                receiver,
                resultFactory.invalidArgument(
                    requestId = id,
                    operation =
                        NavigationContract.OP_SET_DESTINATION,
                    message =
                        "requestId and destinationId must not be blank."
                )
            )
            return
        }

        executor.execute {
            val key =
                RequestKey(
                    id,
                    NavigationContract.OP_SET_DESTINATION
                )
            val accepted =
                resultFactory.accepted(
                    requestId = id,
                    operation = key.operation,
                    message = "Route calculation accepted.",
                    navigationState =
                        NavigationContract.STATE_CALCULATING
                )

            when (
                begin(
                    key = key,
                    fingerprint = token,
                    accepted = accepted,
                    callback = receiver
                )
            ) {
                BeginDecision.NEW -> {
                    when (
                        val resolution =
                            repository.resolveDestination(token)
                    ) {
                        DestinationResolution.Expired -> {
                            finish(
                                key,
                                resultFactory
                                    .destinationResolutionFailure(
                                        requestId = id,
                                        errorCode =
                                            NavigationContract
                                                .ERROR_DESTINATION_EXPIRED,
                                        message =
                                            "The destination ID has expired."
                                    )
                            )
                        }
                        DestinationResolution.Unknown -> {
                            finish(
                                key,
                                resultFactory
                                    .destinationResolutionFailure(
                                        requestId = id,
                                        errorCode =
                                            NavigationContract
                                                .ERROR_DESTINATION_NOT_FOUND,
                                        message =
                                            "The destination ID is unknown."
                                    )
                            )
                        }
                        is DestinationResolution.Found -> {
                            startRoute(
                                key = key,
                                requestId = id,
                                destination =
                                    resolution.destination
                            )
                        }
                    }
                }
                BeginDecision.HANDLED -> Unit
            }
        }
    }

    fun cancelNavigation(
        requestId: String?,
        callback: INavigationCommandCallback?
    ) {
        val receiver = callback ?: return
        val id = requestId?.trim().orEmpty()
        if (id.isBlank()) {
            executeDelivery(
                receiver,
                resultFactory.invalidArgument(
                    requestId = id,
                    operation =
                        NavigationContract.OP_CANCEL_NAVIGATION,
                    message = "requestId must not be blank."
                )
            )
            return
        }

        executor.execute {
            val key =
                RequestKey(
                    id,
                    NavigationContract.OP_CANCEL_NAVIGATION
                )
            val accepted =
                resultFactory.accepted(
                    requestId = id,
                    operation = key.operation,
                    message = "Navigation cancellation accepted.",
                    navigationState =
                        resultFactory.currentContractState()
                )

            when (
                begin(
                    key = key,
                    fingerprint = key.operation,
                    accepted = accepted,
                    callback = receiver
                )
            ) {
                BeginDecision.NEW -> {
                    repository.cancelNavigation()
                    val state =
                        repository.currentNavigationState()
                    val result =
                        if (
                            state.status ==
                            NavigationSessionStatus.IDLE
                        ) {
                            NavigationResult(
                                id,
                                key.operation,
                                HyperNovaContract.STATUS_CONFIRMED,
                                "Navigation is idle.",
                                HyperNovaContract.ERROR_NONE,
                                emptyList(),
                                null,
                                NavigationContract.STATE_IDLE,
                                UNAVAILABLE,
                                UNAVAILABLE
                            )
                        } else {
                            NavigationResult(
                                id,
                                key.operation,
                                HyperNovaContract.STATUS_UNAVAILABLE,
                                "Navigation could not be cancelled.",
                                HyperNovaContract.ERROR_INTERNAL,
                                emptyList(),
                                null,
                                resultFactory
                                    .currentContractState(),
                                UNAVAILABLE,
                                UNAVAILABLE
                            )
                        }
                    finish(key, result)
                }
                BeginDecision.HANDLED -> Unit
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun startRoute(
        key: RequestKey,
        requestId: String,
        destination: ResolvedDestination
    ) {
        val generation =
            repository.startNavigation(destination) { result ->
                result.fold(
                    onSuccess = { state ->
                        finish(
                            key,
                            resultFactory.activeRouteResult(
                                requestId,
                                destination,
                                state
                            )
                        )
                    },
                    onFailure = { failure ->
                        finish(
                            key,
                            resultFactory.providerFailure(
                                requestId = requestId,
                                operation = key.operation,
                                failure = failure
                            )
                        )
                    }
                )
            }

        scheduleTimeout(
            key = key,
            timeoutMillis =
                CommandTimeoutPolicy
                    .timeoutMillis(key.operation)
                    ?: return,
            timeoutResult = {
                resultFactory.timeout(
                    requestId = requestId,
                    operation = key.operation,
                    message = "Route calculation timed out.",
                    navigationState =
                        NavigationContract.STATE_IDLE
                )
            },
            beforeDelivery = {
                repository.cancelRoute(generation)
            }
        )
    }

    private fun begin(
        key: RequestKey,
        fingerprint: String,
        accepted: NavigationResult,
        callback: INavigationCommandCallback
    ): BeginDecision =
        when (
            val registration =
                requests.begin(
                    key = key,
                    fingerprint = fingerprint,
                    accepted = accepted,
                    callback = callback
                )
        ) {
            RequestRegistration.New -> {
                safeDeliver(callback, accepted)
                BeginDecision.NEW
            }
            is RequestRegistration.InFlight -> {
                safeDeliver(callback, registration.accepted)
                BeginDecision.HANDLED
            }
            is RequestRegistration.Completed -> {
                safeDeliver(callback, registration.result)
                BeginDecision.HANDLED
            }
            RequestRegistration.Conflict -> {
                safeDeliver(
                    callback,
                    resultFactory.invalidArgument(
                        requestId = key.requestId,
                        operation = key.operation,
                        message =
                            "requestId was reused with different arguments."
                    )
                )
                BeginDecision.HANDLED
            }
        }

    private fun finish(
        key: RequestKey,
        result: NavigationResult,
        beforeDelivery: () -> Unit = {}
    ) {
        val completion =
            requests.complete(key, result) ?: return
        beforeDelivery()
        completion.callbacks.forEach {
            safeDeliver(it, result)
        }
    }

    private fun scheduleTimeout(
        key: RequestKey,
        timeoutMillis: Long,
        timeoutResult: () -> NavigationResult,
        beforeDelivery: () -> Unit = {}
    ) {
        executor.schedule(
            {
                finish(
                    key = key,
                    result = timeoutResult(),
                    beforeDelivery = beforeDelivery
                )
            },
            timeoutMillis,
            TimeUnit.MILLISECONDS
        )
    }

    private fun executeDelivery(
        callback: INavigationCommandCallback,
        result: NavigationResult
    ) {
        executor.execute {
            safeDeliver(callback, result)
        }
    }

    private fun executeRoutePreviewDelivery(
        callback: INavigationRoutePreviewCallback,
        result: NavigationRoutePreviewResult
    ) {
        executor.execute {
            safeDeliverRoutePreview(callback, result)
        }
    }

    private fun safeDeliver(
        callback: INavigationCommandCallback,
        result: NavigationResult
    ) {
        try {
            callback.onResult(result)
        } catch (exception: RemoteException) {
            Log.i(
                TAG,
                "Navigation callback client disconnected: " +
                    result.requestId
            )
        } catch (exception: RuntimeException) {
            Log.w(
                TAG,
                "Navigation callback failed: ${result.requestId}",
                exception
            )
        }
    }

    private fun safeDeliverRoutePreview(
        callback: INavigationRoutePreviewCallback,
        result: NavigationRoutePreviewResult
    ) {
        try {
            callback.onResult(result)
        } catch (exception: RemoteException) {
            Log.i(
                TAG,
                "Navigation route-preview client disconnected: " +
                    result.requestId
            )
        } catch (exception: RuntimeException) {
            Log.w(
                TAG,
                "Navigation route-preview callback failed: ${result.requestId}",
                exception
            )
        }
    }

    private enum class BeginDecision {
        NEW,
        HANDLED
    }

    companion object {
        private const val TAG = "HN-NavigationAidl"
        private const val UNAVAILABLE = -1L
    }
}
