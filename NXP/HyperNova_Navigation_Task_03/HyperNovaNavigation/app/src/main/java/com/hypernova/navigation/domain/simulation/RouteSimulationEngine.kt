package com.hypernova.navigation.domain.simulation

import com.hypernova.navigation.domain.model.RouteAlternative
import com.hypernova.navigation.domain.model.VehiclePosition
import kotlin.math.sqrt

internal class RouteSimulationEngine(
    private val route: RouteAlternative,
    private val config: RouteSimulationConfig
) {
    private val points = route.points
    private val cumulativeDistances =
        RouteSimulationMath.cumulativeDistances(points)
    val totalDistanceMeters: Double =
        cumulativeDistances.lastOrNull() ?: 0.0
    val baseSpeedMetersPerSecond: Double =
        when {
            route.distanceMeters > 0.0 &&
                route.durationSeconds > 0.0 ->
                route.distanceMeters / route.durationSeconds
            totalDistanceMeters > 0.0 &&
                route.durationSeconds > 0.0 ->
                totalDistanceMeters / route.durationSeconds
            else -> config.fallbackSpeedMetersPerSecond
        }.coerceAtLeast(MINIMUM_BASE_SPEED_METERS_PER_SECOND)

    private var traveledMeters = 0.0
    private var reportedBearing =
        RouteSimulationMath.pointAlong(
            points,
            cumulativeDistances,
            0.0
        )?.bearingDegrees ?: 0.0

    fun initialPosition(): VehiclePosition? =
        positionFor(
            distanceMeters = 0.0,
            speedMetersPerSecond =
                if (totalDistanceMeters > 0.0 && points.size > 1) {
                    baseSpeedMetersPerSecond
                } else {
                    0.0
                },
            arrived = totalDistanceMeters <= 0.0
        )

    fun advance(realElapsedSeconds: Double): VehiclePosition? {
        if (points.isEmpty()) return null
        if (totalDistanceMeters <= 0.0) {
            traveledMeters = 0.0
            return positionFor(0.0, 0.0, arrived = true)
        }

        val remaining = totalDistanceMeters - traveledMeters
        val speedFraction = arrivalSpeedFraction(remaining)
        val speedMetersPerSecond =
            baseSpeedMetersPerSecond * speedFraction
        traveledMeters =
            (traveledMeters +
                speedMetersPerSecond *
                config.speedFactor *
                realElapsedSeconds.coerceAtLeast(0.0))
                .coerceAtMost(totalDistanceMeters)
        val arrived = traveledMeters >= totalDistanceMeters

        return positionFor(
            traveledMeters,
            if (arrived) 0.0 else speedMetersPerSecond,
            arrived
        )
    }

    private fun positionFor(
        distanceMeters: Double,
        speedMetersPerSecond: Double,
        arrived: Boolean
    ): VehiclePosition? {
        val sample =
            RouteSimulationMath.pointAlong(
                points,
                cumulativeDistances,
                distanceMeters
            ) ?: return null
        val targetBearing = sample.bearingDegrees
        reportedBearing =
            if (arrived) {
                targetBearing
            } else {
                RouteSimulationMath.normalizeBearing(
                    reportedBearing +
                        RouteSimulationMath.angleDelta(
                            reportedBearing,
                            targetBearing
                        ) * config.bearingSmoothing.coerceIn(0.0, 1.0)
                )
            }
        val clampedDistance =
            distanceMeters.coerceIn(0.0, totalDistanceMeters)

        return VehiclePosition(
            point =
                if (arrived) points.last() else sample.point,
            bearingDegrees = reportedBearing,
            speedKph = speedMetersPerSecond * METERS_PER_SECOND_TO_KPH,
            traveledMeters = clampedDistance,
            remainingDistanceMeters =
                (totalDistanceMeters - clampedDistance).coerceAtLeast(0.0),
            progressFraction =
                if (totalDistanceMeters > 0.0) {
                    (clampedDistance / totalDistanceMeters)
                        .coerceIn(0.0, 1.0)
                } else {
                    1.0
                },
            routeSegmentIndex = sample.segmentIndex,
            arrived = arrived
        )
    }

    private fun arrivalSpeedFraction(remainingMeters: Double): Double {
        val slowdownDistance = config.arrivalSlowdownDistanceMeters
        if (slowdownDistance <= 0.0 || remainingMeters >= slowdownDistance) {
            return 1.0
        }

        return sqrt((remainingMeters / slowdownDistance).coerceIn(0.0, 1.0))
            .coerceAtLeast(
                config.minimumArrivalSpeedFraction.coerceIn(0.01, 1.0)
            )
    }

    private companion object {
        const val MINIMUM_BASE_SPEED_METERS_PER_SECOND = 1.0
        const val METERS_PER_SECOND_TO_KPH = 3.6
    }
}
