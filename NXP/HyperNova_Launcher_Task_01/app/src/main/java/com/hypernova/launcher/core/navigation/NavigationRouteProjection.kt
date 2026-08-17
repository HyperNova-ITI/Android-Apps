package com.hypernova.launcher.core.navigation

import kotlin.math.abs
import kotlin.math.cos

internal data class NavigationCanvasPoint(
    val x: Float,
    val y: Float,
)

internal data class NavigationRouteProjectionResult(
    val routePoints: List<NavigationCanvasPoint>,
    val currentPosition: NavigationCanvasPoint?,
)

/** Pure coordinate projection used by the Canvas view and JVM tests. */
internal object NavigationRouteProjection {
    fun project(
        routePoints: List<NavigationPreviewPoint>,
        currentPosition: NavigationPreviewPoint?,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): NavigationRouteProjectionResult? {
        if (
            routePoints.size < 2 ||
            right <= left ||
            bottom <= top
        ) {
            return null
        }

        val minimumLatitude = routePoints.minOf { it.latitude }
        val maximumLatitude = routePoints.maxOf { it.latitude }
        val middleLatitudeRadians =
            Math.toRadians((minimumLatitude + maximumLatitude) / 2.0)
        val longitudeScale = abs(cos(middleLatitudeRadians)).coerceAtLeast(0.01)
        val rawX = routePoints.map { it.longitude * longitudeScale }
        val rawY = routePoints.map { -it.latitude }
        val minimumX = rawX.min()
        val maximumX = rawX.max()
        val minimumY = rawY.min()
        val maximumY = rawY.max()
        val rangeX = maximumX - minimumX
        val rangeY = maximumY - minimumY
        val availableWidth = (right - left).toDouble()
        val availableHeight = (bottom - top).toDouble()
        val scale = when {
            rangeX > EPSILON && rangeY > EPSILON ->
                minOf(availableWidth / rangeX, availableHeight / rangeY)
            rangeX > EPSILON -> availableWidth / rangeX
            rangeY > EPSILON -> availableHeight / rangeY
            else -> 1.0
        }
        val projectedWidth = rangeX * scale
        val projectedHeight = rangeY * scale
        val offsetX = left + (availableWidth - projectedWidth) / 2.0
        val offsetY = top + (availableHeight - projectedHeight) / 2.0

        fun projectPoint(point: NavigationPreviewPoint): NavigationCanvasPoint {
            val x =
                offsetX +
                    (point.longitude * longitudeScale - minimumX) * scale
            val y = offsetY + (-point.latitude - minimumY) * scale
            return NavigationCanvasPoint(
                x = x.coerceIn(left.toDouble(), right.toDouble()).toFloat(),
                y = y.coerceIn(top.toDouble(), bottom.toDouble()).toFloat(),
            )
        }

        return NavigationRouteProjectionResult(
            routePoints = routePoints.map(::projectPoint),
            currentPosition = currentPosition?.let(::projectPoint),
        )
    }

    private const val EPSILON = 1e-12
}
