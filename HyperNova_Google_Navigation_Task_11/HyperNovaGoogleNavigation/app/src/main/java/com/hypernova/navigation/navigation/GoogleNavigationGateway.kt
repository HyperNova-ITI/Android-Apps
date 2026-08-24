// Navigation SDK 7.9.0 still exposes setDestination through its deprecated
// ListenableResultFuture type and initialization errors through deprecated int constants.
@file:Suppress("DEPRECATION")

package com.hypernova.navigation.navigation

import android.app.Activity
import android.app.Application
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.google.android.libraries.navigation.ArrivalEvent
import com.google.android.libraries.navigation.ListenableResultFuture
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.google.android.libraries.navigation.RouteSegment
import com.google.android.libraries.navigation.TimeAndDistance
import com.google.android.libraries.navigation.Waypoint
import com.hypernova.navigation.model.GeoPoint
import com.hypernova.navigation.model.GoogleDestinationRecord
import com.hypernova.navigation.model.RouteData
import com.hypernova.navigation.model.VehiclePosition
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class GoogleNavigationGateway(
    private val application: Application,
    private val listener: NavigationGatewayListener,
) : NavigationGateway {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var navigator: Navigator? = null

    @Volatile
    private var initializing = false

    private var roadSnappedLocationProvider: RoadSnappedLocationProvider? = null

    override val isReady: Boolean
        get() = navigator != null

    override fun initialize(activity: Activity?) {
        if (navigator != null) {
            listener.onNavigatorReady()
            return
        }
        synchronized(this) {
            if (initializing || navigator != null) return
            initializing = true
        }

        val callback =
            object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(readyNavigator: Navigator) {
                    synchronized(this@GoogleNavigationGateway) {
                        initializing = false
                        if (navigator == null) {
                            navigator = readyNavigator
                            installListeners(readyNavigator)
                        }
                    }
                    listener.onNavigatorReady()
                }

                override fun onError(errorCode: Int) {
                    initializing = false
                    listener.onNavigatorInitializationFailed(errorCode.toFailure())
                }
            }

        if (activity != null) {
            NavigationApi.getNavigator(activity, callback)
        } else {
            NavigationApi.getNavigator(application, callback)
        }
    }

    override suspend fun setDestination(destination: GoogleDestinationRecord): GoogleRouteResult =
        withContext(Dispatchers.Main.immediate) {
            val activeNavigator = navigator ?: return@withContext GoogleRouteResult.InternalError("Navigator is not ready.")
            // Frozen API v1 defines setDestination as route preparation. A new
            // destination must never inherit active guidance from the prior route.
            activeNavigator.stopGuidance()
            val waypoint =
                try {
                    Waypoint.builder()
                        .setTitle(destination.title)
                        .setPlaceIdString(destination.placeId)
                        .build()
                } catch (failure: Exception) {
                    return@withContext GoogleRouteResult.InternalError("Google rejected the destination waypoint.")
                }
            val routeStatus = activeNavigator.setDestination(waypoint).awaitResult()
            when (routeStatus) {
                Navigator.RouteStatus.OK ->
                    currentRouteData(activeNavigator)?.let(GoogleRouteResult::Ready)
                        ?: GoogleRouteResult.InternalError("Google route geometry is unavailable.")
                Navigator.RouteStatus.NO_ROUTE_FOUND,
                Navigator.RouteStatus.WAYPOINT_ERROR,
                Navigator.RouteStatus.DUPLICATE_WAYPOINTS_ERROR,
                -> GoogleRouteResult.NoRoute
                Navigator.RouteStatus.NETWORK_ERROR -> GoogleRouteResult.NetworkError
                Navigator.RouteStatus.LOCATION_DISABLED,
                Navigator.RouteStatus.LOCATION_UNKNOWN,
                -> GoogleRouteResult.LocationUnavailable
                Navigator.RouteStatus.ROUTE_CANCELED -> GoogleRouteResult.Cancelled
                Navigator.RouteStatus.QUOTA_CHECK_FAILED -> GoogleRouteResult.AuthorizationError
            }
        }

    override fun startGuidance(): Boolean {
        val activeNavigator = navigator ?: return false
        activeNavigator.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
        activeNavigator.startGuidance()
        return activeNavigator.isGuidanceRunning
    }

    override fun cancelNavigation() {
        val cancellation = {
            navigator?.apply {
                stopGuidance()
                clearDestinations()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cancellation() else mainHandler.post { cancellation() }
    }

    internal fun navigatorForDebug(): Navigator? = navigator

    private fun installListeners(activeNavigator: Navigator) {
        activeNavigator.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.CONTINUE_SERVICE)
        activeNavigator.addArrivalListener { event: ArrivalEvent ->
            if (event.isFinalDestination) listener.onArrival()
        }
        activeNavigator.addReroutingListener {
            listener.onRerouting()
        }
        activeNavigator.addRouteChangedListener {
            currentRouteData(activeNavigator)?.let(listener::onRouteChanged)
        }
        activeNavigator.addRemainingTimeOrDistanceChangedListener(1, 10) {
            activeNavigator.currentTimeAndDistance?.let { metrics ->
                listener.onProgress(metrics.seconds.toLong(), metrics.meters.toLong())
            }
        }
        roadSnappedLocationProvider =
            requireNotNull(NavigationApi.getRoadSnappedLocationProvider(application)).also { provider ->
                provider.addLocationListener(
                    object : RoadSnappedLocationProvider.LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (
                                !location.latitude.isFinite() ||
                                !location.longitude.isFinite() ||
                                location.latitude !in -90.0..90.0 ||
                                location.longitude !in -180.0..180.0
                            ) {
                                return
                            }
                            val bearing =
                                if (location.hasBearing() && location.bearing.isFinite()) {
                                    ((location.bearing % 360f) + 360f) % 360f
                                } else {
                                    Float.NaN
                                }
                            val speed =
                                if (
                                    location.hasSpeed() &&
                                    location.speed.isFinite() &&
                                    location.speed >= 0f
                                ) {
                                    location.speed
                                } else {
                                    Float.NaN
                                }
                            listener.onPosition(
                                VehiclePosition(
                                    point = GeoPoint(location.latitude, location.longitude),
                                    bearingDegrees = bearing,
                                    speedMetersPerSecond = speed,
                                    timestampMillis = location.time,
                                ),
                            )
                        }
                    },
                )
            }
    }

    internal fun currentRouteDataForDebug(): RouteData? =
        navigator?.let(::currentRouteData)

    private fun currentRouteData(activeNavigator: Navigator): RouteData? {
        val segments: List<RouteSegment> = activeNavigator.routeSegments.orEmpty()
        val points =
            segments.flatMap { segment ->
                segment.latLngs.orEmpty().map { GeoPoint(it.latitude, it.longitude) }
            }
        val metrics: TimeAndDistance? = activeNavigator.currentTimeAndDistance
        if (points.size < 2 || metrics == null) return null
        return RouteData(
            points = points,
            etaSeconds = metrics.seconds.toLong(),
            distanceMeters = metrics.meters.toLong(),
        )
    }

    private suspend fun <T> ListenableResultFuture<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            setOnResultListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            continuation.invokeOnCancellation { cancel(true) }
        }

    private fun Int.toFailure(): NavigatorInitializationFailure =
        when (this) {
            NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> NavigatorInitializationFailure.TERMS_NOT_ACCEPTED
            NavigationApi.ErrorCode.NOT_AUTHORIZED -> NavigatorInitializationFailure.NOT_AUTHORIZED
            NavigationApi.ErrorCode.NETWORK_ERROR -> NavigatorInitializationFailure.NETWORK
            NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> NavigatorInitializationFailure.LOCATION_PERMISSION_MISSING
            else -> NavigatorInitializationFailure.INTERNAL
        }
}
