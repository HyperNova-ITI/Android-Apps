package com.hypernova.navigation.session

import com.hypernova.navigation.model.GeoPoint
import kotlin.math.roundToInt

object RouteGeometry {
    fun sanitizeAndBound(points: List<GeoPoint>, maximum: Int): List<GeoPoint> {
        require(maximum >= 2)
        val valid =
            points.filter {
                it.latitude.isFinite() &&
                    it.longitude.isFinite() &&
                    it.latitude in -90.0..90.0 &&
                    it.longitude in -180.0..180.0
            }.withoutAdjacentDuplicates()
        if (valid.size <= maximum) return valid

        val lastIndex = valid.lastIndex
        return List(maximum) { index ->
            val sourceIndex =
                (index.toDouble() * lastIndex / (maximum - 1))
                    .roundToInt()
                    .coerceIn(0, lastIndex)
            valid[sourceIndex]
        }.withoutAdjacentDuplicates()
    }

    private fun List<GeoPoint>.withoutAdjacentDuplicates(): List<GeoPoint> =
        fold(mutableListOf()) { result, point ->
            if (result.lastOrNull() != point) result += point
            result
        }
}
