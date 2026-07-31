package com.hypernova.navigation.service

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.domain.model.NavigationSessionState
import com.hypernova.navigation.domain.model.NavigationSessionStatus

object CommandTimeoutPolicy {
    fun timeoutMillis(operation: String): Long? =
        when (operation) {
            NavigationContract.OP_SEARCH_DESTINATIONS,
            NavigationContract.OP_GET_SAVED_DESTINATIONS ->
                NavigationContract.SEARCH_TIMEOUT_MILLIS
            NavigationContract.OP_SET_DESTINATION ->
                NavigationContract.ROUTE_TIMEOUT_MILLIS
            else -> null
        }
}

object RouteConfirmationPolicy {
    fun canConfirm(
        state: NavigationSessionState,
        destinationId: String
    ): Boolean =
        state.status == NavigationSessionStatus.ACTIVE &&
            state.destination?.id == destinationId &&
            state.routePlan != null
}
