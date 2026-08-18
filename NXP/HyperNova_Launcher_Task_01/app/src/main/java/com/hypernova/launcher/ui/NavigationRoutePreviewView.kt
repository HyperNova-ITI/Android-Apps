package com.hypernova.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.hypernova.launcher.R
import com.hypernova.launcher.core.navigation.NavigationCanvasPoint
import com.hypernova.launcher.core.navigation.NavigationPreviewPoint
import com.hypernova.launcher.core.navigation.NavigationRouteProjection

/** Lightweight read-only rendering of Navigation's bounded OSRM geometry. */
class NavigationRoutePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val routePath = Path()
    private val gridPath = Path()
    private val vehicleArrowPath = Path().apply {
        moveTo(0f, dp(-8f))
        lineTo(dp(6f), dp(7f))
        lineTo(0f, dp(3f))
        lineTo(dp(-6f), dp(7f))
        close()
    }
    private val backgroundPaint = Paint().apply {
        color = color(R.color.navigation_preview_background)
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.navigation_preview_grid)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val routeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.navigation_preview_route_glow)
        style = Paint.Style.STROKE
        strokeWidth = dp(7f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.navigation_preview_route)
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.navigation_preview_marker_outer)
        style = Paint.Style.FILL
    }
    private val markerInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.navigation_preview_marker_inner)
        style = Paint.Style.FILL
    }
    private val destinationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.navigation_preview_destination)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val vehicleOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.hypernova_cyan_dark)
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeJoin = Paint.Join.ROUND
    }
    private val vehiclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.navigation_preview_route)
        style = Paint.Style.FILL
    }

    private var routePoints: List<NavigationPreviewPoint> = emptyList()
    private var currentPosition: NavigationPreviewPoint? = null
    private var currentBearingDegrees: Float? = null
    private var projectedCurrentPosition: NavigationCanvasPoint? = null
    private var projectedStart: NavigationCanvasPoint? = null
    private var projectedDestination: NavigationCanvasPoint? = null

    fun setRoute(
        points: List<NavigationPreviewPoint>,
        currentPosition: NavigationPreviewPoint?,
        currentBearingDegrees: Float?,
    ) {
        if (
            routePoints == points &&
            this.currentPosition == currentPosition &&
            this.currentBearingDegrees == currentBearingDegrees
        ) {
            return
        }
        routePoints = points.toList()
        this.currentPosition = currentPosition
        this.currentBearingDegrees = currentBearingDegrees
        rebuildRoutePath()
        invalidate()
    }

    fun clearRoute() {
        if (routePoints.isEmpty() && currentPosition == null) return
        routePoints = emptyList()
        currentPosition = null
        currentBearingDegrees = null
        routePath.reset()
        projectedCurrentPosition = null
        projectedStart = null
        projectedDestination = null
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuildGridPath(width, height)
        rebuildRoutePath()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        canvas.drawPath(gridPath, gridPaint)

        if (routePath.isEmpty) return

        canvas.drawPath(routePath, routeGlowPaint)
        canvas.drawPath(routePath, routePaint)

        projectedCurrentPosition?.let { marker ->
            val bearing = currentBearingDegrees
            if (bearing != null) {
                canvas.save()
                canvas.translate(marker.x, marker.y)
                canvas.rotate(bearing)
                canvas.drawPath(vehicleArrowPath, vehicleOutlinePaint)
                canvas.drawPath(vehicleArrowPath, vehiclePaint)
                canvas.restore()
            } else {
                canvas.drawCircle(marker.x, marker.y, dp(6f), markerOuterPaint)
                canvas.drawCircle(marker.x, marker.y, dp(2.5f), markerInnerPaint)
            }
        } ?: projectedStart?.let { marker ->
            canvas.drawCircle(marker.x, marker.y, dp(6f), markerOuterPaint)
            canvas.drawCircle(marker.x, marker.y, dp(2.5f), markerInnerPaint)
        }

        projectedDestination?.let { marker ->
            canvas.drawCircle(marker.x, marker.y, dp(6f), markerOuterPaint)
            canvas.drawCircle(marker.x, marker.y, dp(4f), destinationPaint)
            canvas.drawCircle(marker.x, marker.y, dp(1.5f), markerInnerPaint)
        }
    }

    private fun rebuildRoutePath() {
        routePath.reset()
        projectedCurrentPosition = null
        projectedStart = null
        projectedDestination = null

        val projection = NavigationRouteProjection.project(
            routePoints = routePoints,
            currentPosition = currentPosition,
            left = paddingLeft.toFloat(),
            top = paddingTop.toFloat(),
            right = (width - paddingRight).toFloat(),
            bottom = (height - paddingBottom).toFloat(),
        ) ?: return

        projection.routePoints.forEachIndexed { index, point ->
            if (index == 0) {
                routePath.moveTo(point.x, point.y)
            } else {
                routePath.lineTo(point.x, point.y)
            }
        }
        projectedStart = projection.routePoints.first()
        projectedDestination = projection.routePoints.last()
        projectedCurrentPosition = projection.currentPosition
    }

    private fun rebuildGridPath(width: Int, height: Int) {
        gridPath.reset()
        val spacing = dp(32f)
        val widthPx = width.toFloat()
        val heightPx = height.toFloat()
        var x = 0f
        while (x <= widthPx) {
            gridPath.moveTo(x, 0f)
            gridPath.lineTo(x, heightPx)
            x += spacing
        }
        var y = 0f
        while (y <= heightPx) {
            gridPath.moveTo(0f, y)
            gridPath.lineTo(widthPx, y)
            y += spacing
        }
        var diagonal = -heightPx
        while (diagonal <= widthPx) {
            gridPath.moveTo(diagonal, 0f)
            gridPath.lineTo(diagonal + heightPx, heightPx)
            diagonal += spacing * 2f
        }
    }

    private fun color(resource: Int): Int = ContextCompat.getColor(context, resource)

    private fun dp(value: Float): Float = value * density
}
