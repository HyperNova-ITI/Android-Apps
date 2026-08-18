package com.hypernova.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.hypernova.launcher.R
import com.hypernova.launcher.core.navigation.NavigationPreviewPoint
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/** Draws Navigation's route preview over the real MapLibre HOME map. */
class LauncherSoftwareRouteOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val routePath = Path()
    private var map: MapLibreMap? = null
    private var routePoints: List<NavigationPreviewPoint> = emptyList()

    private val casingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.hypernova_cyan_dark)
        style = Paint.Style.STROKE
        strokeWidth = dp(10f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.hypernova_cyan)
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
    }

    fun setMap(value: MapLibreMap?) {
        map = value
        invalidate()
    }

    fun setRoutePoints(points: List<NavigationPreviewPoint>) {
        routePoints = points.toList()
        invalidate()
    }

    fun clearRoute() {
        if (routePoints.isEmpty()) return
        routePoints = emptyList()
        routePath.reset()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (routePoints.size < 2) return

        val projection = map?.projection ?: return
        routePath.reset()
        routePoints.forEachIndexed { index, point ->
            if (!point.latitude.isFinite() || !point.longitude.isFinite()) {
                routePath.reset()
                return
            }
            val screenPoint = projection.toScreenLocation(
                LatLng(point.latitude, point.longitude),
            )
            if (!screenPoint.x.isFinite() || !screenPoint.y.isFinite()) {
                routePath.reset()
                return
            }
            if (index == 0) {
                routePath.moveTo(screenPoint.x, screenPoint.y)
            } else {
                routePath.lineTo(screenPoint.x, screenPoint.y)
            }
        }

        if (!routePath.isEmpty) {
            canvas.drawPath(routePath, casingPaint)
            canvas.drawPath(routePath, routePaint)
        }
    }

    private fun dp(value: Float): Float = value * density
}
