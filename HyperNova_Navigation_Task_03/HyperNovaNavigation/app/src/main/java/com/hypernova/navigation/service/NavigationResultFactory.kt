package com.hypernova.navigation.service

import android.util.Log
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationDestination
import com.hypernova.contracts.navigation.NavigationResult
import com.hypernova.contracts.navigation.NavigationRoutePreview
import com.hypernova.contracts.navigation.NavigationRoutePreviewResult
import com.hypernova.navigation.domain.model.DestinationSource
import com.hypernova.navigation.domain.model.FailureKind
import com.hypernova.navigation.domain.model.NavigationDataException
import com.hypernova.navigation.domain.model.NavigationSessionState
import com.hypernova.navigation.domain.model.NavigationSessionStatus
import com.hypernova.navigation.domain.model.ResolvedDestination
import kotlin.math.roundToLong

class NavigationResultFactory(
    private val stateProvider: () -> NavigationSessionState
) {
    fun searchResult(
        requestId: String,
        destinations: List<ResolvedDestination>
    ): NavigationResult =
        if (destinations.isEmpty()) {
            NavigationResult(
                requestId,
                NavigationContract.OP_SEARCH_DESTINATIONS,
                HyperNovaContract.STATUS_REJECTED,
                "No destinations found.",
                NavigationContract.ERROR_NO_RESULTS,
                emptyList(),
                null,
                currentContractState(),
                UNAVAILABLE,
                UNAVAILABLE
            )
        } else {
            NavigationResult(
                requestId,
                NavigationContract.OP_SEARCH_DESTINATIONS,
                HyperNovaContract.STATUS_CONFIRMED,
                "Destinations found.",
                HyperNovaContract.ERROR_NONE,
                destinations.map { it.toContract() },
                null,
                currentContractState(),
                UNAVAILABLE,
                UNAVAILABLE
            )
        }

    fun savedResult(
        requestId: String,
        destinations: List<ResolvedDestination>
    ): NavigationResult =
        if (destinations.isEmpty()) {
            NavigationResult(
                requestId,
                NavigationContract.OP_GET_SAVED_DESTINATIONS,
                HyperNovaContract.STATUS_REJECTED,
                "No saved destinations are configured.",
                NavigationContract.ERROR_NO_SAVED_DESTINATIONS,
                emptyList(),
                null,
                currentContractState(),
                UNAVAILABLE,
                UNAVAILABLE
            )
        } else {
            NavigationResult(
                requestId,
                NavigationContract.OP_GET_SAVED_DESTINATIONS,
                HyperNovaContract.STATUS_CONFIRMED,
                "Saved destinations found.",
                HyperNovaContract.ERROR_NONE,
                destinations.map { it.toContract() },
                null,
                currentContractState(),
                UNAVAILABLE,
                UNAVAILABLE
            )
        }

    fun destinationSetResult(
        requestId: String,
        destination: ResolvedDestination,
        state: NavigationSessionState
    ): NavigationResult {
        val route =
            state.routePlan?.selected
        val isAuthoritativelyReady =
            RouteConfirmationPolicy.canConfirm(
                state = state,
                destinationId = destination.id
            )

        return if (isAuthoritativelyReady && route != null) {
            NavigationResult(
                requestId,
                NavigationContract.OP_SET_DESTINATION,
                HyperNovaContract.STATUS_CONFIRMED,
                "Destination set to ${destination.place.name}. Route is ready.",
                HyperNovaContract.ERROR_NONE,
                emptyList(),
                destination.toContract(),
                NavigationContract.STATE_IDLE,
                route.durationSeconds.roundToLong(),
                route.distanceMeters.roundToLong()
            )
        } else {
            NavigationResult(
                requestId,
                NavigationContract.OP_SET_DESTINATION,
                HyperNovaContract.STATUS_UNAVAILABLE,
                "The route preview was not prepared.",
                HyperNovaContract.ERROR_INTERNAL,
                emptyList(),
                destination.toContract(),
                state.toContractState(),
                UNAVAILABLE,
                UNAVAILABLE
            )
        }
    }

    fun destinationResolutionFailure(
        requestId: String,
        errorCode: String,
        message: String
    ): NavigationResult =
        NavigationResult(
            requestId,
            NavigationContract.OP_SET_DESTINATION,
            HyperNovaContract.STATUS_REJECTED,
            message,
            errorCode,
            emptyList(),
            null,
            currentContractState(),
            UNAVAILABLE,
            UNAVAILABLE
        )

    fun providerFailure(
        requestId: String,
        operation: String,
        failure: Throwable
    ): NavigationResult {
        val dataFailure =
            failure as? NavigationDataException
        val status =
            when (dataFailure?.kind) {
                FailureKind.TIMEOUT ->
                    HyperNovaContract.STATUS_TIMEOUT
                FailureKind.CANCELLED ->
                    HyperNovaContract.STATUS_CANCELLED
                FailureKind.NO_ROUTE ->
                    HyperNovaContract.STATUS_REJECTED
                else ->
                    HyperNovaContract.STATUS_UNAVAILABLE
            }
        val errorCode =
            when (dataFailure?.kind) {
                FailureKind.TIMEOUT ->
                    HyperNovaContract.ERROR_TIMEOUT
                FailureKind.LOCATION_UNAVAILABLE ->
                    NavigationContract.ERROR_LOCATION_UNAVAILABLE
                FailureKind.NO_ROUTE ->
                    NavigationContract.ERROR_ROUTE_NOT_FOUND
                FailureKind.NETWORK ->
                    NavigationContract
                        .ERROR_OFFLINE_DATA_UNAVAILABLE
                FailureKind.CANCELLED ->
                    HyperNovaContract.ERROR_NONE
                FailureKind.PROVIDER ->
                    HyperNovaContract.ERROR_SERVICE_UNAVAILABLE
                else ->
                    HyperNovaContract.ERROR_INTERNAL
            }

        return NavigationResult(
            requestId,
            operation,
            status,
            failure.message
                ?: "The navigation request failed.",
            errorCode,
            emptyList(),
            null,
            currentContractState(),
            UNAVAILABLE,
            UNAVAILABLE
        )
    }

    fun internalFailure(
        requestId: String,
        operation: String,
        failure: Throwable
    ): NavigationResult {
        Log.e(TAG, "Navigation command failed", failure)
        return NavigationResult(
            requestId,
            operation,
            HyperNovaContract.STATUS_UNAVAILABLE,
            "The navigation service is unavailable.",
            HyperNovaContract.ERROR_INTERNAL,
            emptyList(),
            null,
            currentContractState(),
            UNAVAILABLE,
            UNAVAILABLE
        )
    }

    fun invalidArgument(
        requestId: String,
        operation: String,
        message: String
    ): NavigationResult =
        NavigationResult(
            requestId,
            operation,
            HyperNovaContract.STATUS_REJECTED,
            message,
            HyperNovaContract.ERROR_INVALID_ARGUMENT,
            emptyList(),
            null,
            currentContractState(),
            UNAVAILABLE,
            UNAVAILABLE
        )

    fun accepted(
        requestId: String,
        operation: String,
        message: String,
        navigationState: Int
    ): NavigationResult =
        NavigationResult(
            requestId,
            operation,
            HyperNovaContract.STATUS_ACCEPTED,
            message,
            HyperNovaContract.ERROR_NONE,
            emptyList(),
            null,
            navigationState,
            UNAVAILABLE,
            UNAVAILABLE
        )

    fun timeout(
        requestId: String,
        operation: String,
        message: String,
        navigationState: Int =
            currentContractState()
    ): NavigationResult =
        NavigationResult(
            requestId,
            operation,
            HyperNovaContract.STATUS_TIMEOUT,
            message,
            HyperNovaContract.ERROR_TIMEOUT,
            emptyList(),
            null,
            navigationState,
            UNAVAILABLE,
            UNAVAILABLE
        )

    /** Build a read-only snapshot of the shared Navigation session. */
    fun currentStateResult(
        requestId: String,
        state: NavigationSessionState = stateProvider()
    ): NavigationResult {
        val route =
            if (
                state.status == NavigationSessionStatus.ACTIVE ||
                state.status == NavigationSessionStatus.ARRIVED
            ) {
                state.routePlan?.let { plan ->
                    if (plan.alternatives.isEmpty()) null else plan.selected
                }
            } else {
                null
            }
        val etaSeconds =
            route?.durationSeconds
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.roundToLong()
                ?: UNAVAILABLE
        val distanceMeters =
            route?.distanceMeters
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.roundToLong()
                ?: UNAVAILABLE

        return NavigationResult(
            requestId,
            NavigationContract.OP_GET_CURRENT_STATE,
            HyperNovaContract.STATUS_CONFIRMED,
            state.currentStateMessage(),
            HyperNovaContract.ERROR_NONE,
            emptyList(),
            state.destination?.toContract(),
            state.toContractState(),
            etaSeconds,
            distanceMeters
        )
    }

    /** Build the separate additive route-preview response without changing NavigationResult ABI. */
    fun currentRoutePreviewResult(
        requestId: String,
        state: NavigationSessionState = stateProvider()
    ): NavigationRoutePreviewResult {
        val preview = NavigationRoutePreviewMapper.fromState(state)
        return NavigationRoutePreviewResult(
            requestId,
            HyperNovaContract.STATUS_CONFIRMED,
            if (preview.routePoints.isEmpty()) {
                "Route preview is unavailable."
            } else {
                "Route preview is available."
            },
            HyperNovaContract.ERROR_NONE,
            state.toContractState(),
            preview
        )
    }

    fun invalidRoutePreviewArgument(requestId: String): NavigationRoutePreviewResult =
        NavigationRoutePreviewResult(
            requestId,
            HyperNovaContract.STATUS_REJECTED,
            "requestId must not be blank.",
            HyperNovaContract.ERROR_INVALID_ARGUMENT,
            currentContractState(),
            NavigationRoutePreview.empty()
        )

    fun routePreviewFailure(
        requestId: String,
        failure: Throwable
    ): NavigationRoutePreviewResult {
        Log.e(TAG, "Navigation route preview failed", failure)
        return NavigationRoutePreviewResult(
            requestId,
            HyperNovaContract.STATUS_UNAVAILABLE,
            "The navigation route preview is unavailable.",
            HyperNovaContract.ERROR_INTERNAL,
            currentContractState(),
            NavigationRoutePreview.empty()
        )
    }

    fun currentContractState(): Int =
        stateProvider().toContractState()

    private fun NavigationSessionState.currentStateMessage(): String =
        when (status) {
            NavigationSessionStatus.IDLE ->
                "Navigation is idle."
            NavigationSessionStatus.CALCULATING ->
                "Route calculation is in progress."
            NavigationSessionStatus.ROUTE_PREVIEW ->
                "A route is ready to start."
            NavigationSessionStatus.ACTIVE ->
                "Navigation is active."
            NavigationSessionStatus.ARRIVED ->
                "The destination has been reached."
            NavigationSessionStatus.ERROR ->
                message?.takeIf { it.isNotBlank() }
                    ?: "Navigation is in an error state."
        }

    private fun NavigationSessionState.toContractState(): Int =
        when (status) {
            NavigationSessionStatus.IDLE,
            NavigationSessionStatus.ROUTE_PREVIEW ->
                NavigationContract.STATE_IDLE
            NavigationSessionStatus.CALCULATING ->
                NavigationContract.STATE_CALCULATING
            NavigationSessionStatus.ACTIVE ->
                NavigationContract.STATE_ACTIVE
            NavigationSessionStatus.ARRIVED ->
                NavigationContract.STATE_ARRIVED
            NavigationSessionStatus.ERROR ->
                NavigationContract.STATE_ERROR
        }

    private fun ResolvedDestination.toContract():
        NavigationDestination =
        NavigationDestination(
            id,
            source.toContractSource(),
            place.name,
            place.address.ifBlank { place.displayName },
            place.categoryDescription
                .ifBlank {
                    place.category.ifBlank { place.type }
                },
            distanceMeters ?: UNAVAILABLE
        )

    private fun DestinationSource.toContractSource(): Int =
        when (this) {
            DestinationSource.SEARCH ->
                NavigationContract.SOURCE_SEARCH
            DestinationSource.SAVED_HOME ->
                NavigationContract.SOURCE_SAVED_HOME
            DestinationSource.SAVED_WORK ->
                NavigationContract.SOURCE_SAVED_WORK
            DestinationSource.SAVED_FAVORITE ->
                NavigationContract.SOURCE_SAVED_FAVORITE
        }

    companion object {
        private const val TAG = "HN-NavigationAidl"
        private const val UNAVAILABLE = -1L
    }
}
