package com.hypernova.navigation.domain.simulation

import com.hypernova.navigation.domain.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object RouteSimulationMath {
    data class PointAlongRoute(
        val point: GeoPoint,
        val bearingDegrees: Double,
        val segmentIndex: Int
    )

    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val latitudeDelta =
            Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta =
            Math.toRadians(to.longitude - from.longitude)
        val haversine =
            sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
                cos(fromLatitude) * cos(toLatitude) *
                sin(longitudeDelta / 2.0) *
                sin(longitudeDelta / 2.0)

        val clampedHaversine = haversine.coerceIn(0.0, 1.0)
        return EARTH_RADIUS_METERS *
            2.0 *
            atan2(
                sqrt(clampedHaversine),
                sqrt(1.0 - clampedHaversine)
            )
    }

    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        if (from == to) return 0.0

        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val longitudeDelta =
            Math.toRadians(to.longitude - from.longitude)
        val y = sin(longitudeDelta) * cos(toLatitude)
        val x =
            cos(fromLatitude) * sin(toLatitude) -
                sin(fromLatitude) * cos(toLatitude) *
                cos(longitudeDelta)

        return normalizeBearing(Math.toDegrees(atan2(y, x)))
    }

    fun lerp(
        from: GeoPoint,
        to: GeoPoint,
        fraction: Double
    ): GeoPoint {
        val clamped = fraction.coerceIn(0.0, 1.0)
        return GeoPoint(
            latitude =
                from.latitude +
                    (to.latitude - from.latitude) * clamped,
            longitude =
                from.longitude +
                    (to.longitude - from.longitude) * clamped
        )
    }

    fun cumulativeDistances(points: List<GeoPoint>): DoubleArray {
        if (points.isEmpty()) return DoubleArray(0)

        return DoubleArray(points.size).also { cumulative ->
            for (index in 1 until points.size) {
                cumulative[index] =
                    cumulative[index - 1] +
                        distanceMeters(points[index - 1], points[index])
            }
        }
    }

    fun pointAlong(
        points: List<GeoPoint>,
        cumulativeDistances: DoubleArray,
        traveledMeters: Double
    ): PointAlongRoute? {
        if (points.isEmpty()) return null
        if (points.size == 1) {
            return PointAlongRoute(points.first(), 0.0, 0)
        }

        val cumulative =
            if (cumulativeDistances.size == points.size) {
                cumulativeDistances
            } else {
                cumulativeDistances(points)
            }
        val totalDistance = cumulative.last()
        val clampedDistance =
            traveledMeters.coerceIn(0.0, totalDistance)

        var segmentIndex = 0
        while (
            segmentIndex < points.lastIndex - 1 &&
            cumulative[segmentIndex + 1] <= clampedDistance
        ) {
            segmentIndex++
        }

        while (
            segmentIndex < points.lastIndex - 1 &&
            cumulative[segmentIndex + 1] ==
            cumulative[segmentIndex]
        ) {
            segmentIndex++
        }

        val startDistance = cumulative[segmentIndex]
        val endDistance = cumulative[segmentIndex + 1]
        val segmentDistance = endDistance - startDistance
        val fraction =
            if (segmentDistance > 0.0) {
                (clampedDistance - startDistance) / segmentDistance
            } else {
                1.0
            }

        return PointAlongRoute(
            point = lerp(
                points[segmentIndex],
                points[segmentIndex + 1],
                fraction
            ),
            bearingDegrees =
                bearingDegrees(
                    points[segmentIndex],
                    points[segmentIndex + 1]
                ),
            segmentIndex = segmentIndex
        )
    }

    fun angleDelta(
        currentBearing: Double,
        targetBearing: Double
    ): Double {
        var delta =
            (targetBearing - currentBearing + 540.0) % 360.0 - 180.0
        if (delta <= -180.0) delta += 360.0
        return delta
    }

    fun normalizeBearing(bearing: Double): Double =
        (bearing % 360.0 + 360.0) % 360.0

    private const val EARTH_RADIUS_METERS = 6_371_008.8
}
