package com.hypernova.navigation.navigation

import android.app.Activity
import android.view.ViewGroup
import com.hypernova.navigation.model.RouteData
import com.hypernova.navigation.model.VehiclePosition
import com.hypernova.navigation.model.GoogleDestinationRecord

enum class NavigatorInitializationFailure {
    TERMS_NOT_ACCEPTED,
    NOT_AUTHORIZED,
    NETWORK,
    LOCATION_PERMISSION_MISSING,
    INTERNAL,
}

sealed interface GoogleRouteResult {
    data class Ready(
        val route: RouteData,
        val usesDemoOrigin: Boolean = false,
    ) : GoogleRouteResult
    data object NoRoute : GoogleRouteResult
    data object NetworkError : GoogleRouteResult
    data object LocationUnavailable : GoogleRouteResult
    data object Cancelled : GoogleRouteResult
    data object AuthorizationError : GoogleRouteResult
    data class InternalError(val message: String) : GoogleRouteResult
}

interface NavigationGatewayListener {
    fun onNavigatorReady()
    fun onNavigatorInitializationFailed(failure: NavigatorInitializationFailure)
    fun onMapDestinationRequested(destination: GoogleDestinationRecord)
    fun onRouteChanged(route: RouteData)
    fun onRerouting()
    fun onProgress(etaSeconds: Long, distanceMeters: Long)
    fun onPosition(position: VehiclePosition)
    fun onArrival()
}

interface NavigationGateway {
    val isReady: Boolean
    val supportsGuidance: Boolean
    fun initialize(activity: Activity? = null)
    fun attachSurface(container: ViewGroup)
    fun detachSurface(container: ViewGroup)
    fun setSurfaceInsets(topPixels: Int, bottomPixels: Int)
    suspend fun setDestination(destination: GoogleDestinationRecord): GoogleRouteResult
    fun startGuidance(): Boolean
    fun cancelNavigation()
}
