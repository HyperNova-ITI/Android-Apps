package com.hypernova.navigation.service

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationRoutePoint
import com.hypernova.contracts.navigation.NavigationRoutePreview
import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.NavigationSessionState
import com.hypernova.navigation.domain.model.NavigationSessionStatus

/** Maps bounded authoritative route geometry into the cross-app contract. */
internal object NavigationRoutePreviewMapper {
    fun fromState(state: NavigationSessionState): NavigationRoutePreview {
        val sourcePoints =
            when (state.status) {
                NavigationSessionStatus.CALCULATING,
                NavigationSessionStatus.ACTIVE,
                NavigationSessionStatus.ARRIVED ->
                    state.routePlan?.let { plan ->
                        if (plan.alternatives.isEmpty()) {
                            emptyList()
                        } else {
                            plan.selected.points
                        }
                    }.orEmpty()
                NavigationSessionStatus.IDLE,
                NavigationSessionStatus.ROUTE_PREVIEW,
                NavigationSessionStatus.ERROR -> emptyList()
            }

        val routePoints = simplifyRoutePoints(sourcePoints)
        if (routePoints.size < 2) {
            return NavigationRoutePreview.empty()
        }

        val currentPosition =
            state.vehiclePosition
                ?.point
                ?.takeIf(::isValidCoordinate)
                ?.toContract()

        return NavigationRoutePreview(
            routePoints.map { it.toContract() },
            currentPosition
        )
    }

    internal fun simplifyRoutePoints(
        points: List<GeoPoint>,
        maximumPoints: Int = NavigationContract.MAX_ROUTE_PREVIEW_POINTS
    ): List<GeoPoint> {
        if (maximumPoints < 2) return emptyList()

        val validPoints = points.filter(::isValidCoordinate)
        if (validPoints.size <= maximumPoints) {
            return validPoints.toList()
        }

        val lastIndex = validPoints.lastIndex
        return List(maximumPoints) { outputIndex ->
            val sourceIndex =
                (outputIndex.toLong() * lastIndex / (maximumPoints - 1L))
                    .toInt()
            validPoints[sourceIndex]
        }
    }

    private fun isValidCoordinate(point: GeoPoint): Boolean =
        point.latitude.isFinite() &&
            point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 &&
            point.longitude in -180.0..180.0

    private fun GeoPoint.toContract(): NavigationRoutePoint =
        NavigationRoutePoint(latitude, longitude)
}
