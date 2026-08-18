package com.hypernova.climate.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.hypernova.climate.R
import kotlin.math.min

/**
 * A 270° circular temperature gauge (README §17). Draws an unfilled track arc
 * and a filled progress arc; the temperature text is overlaid by the layout on
 * top (a centered TextView), so this view only renders the arc.
 *
 * The arc opens at the bottom: it spans 270° starting at 135° (lower-left),
 * sweeping clockwise through left → top → right, leaving a 90° gap centered at
 * the bottom.
 *
 * Accent color is set per zone (cyan for driver, amber for passenger) via the
 * `gaugeAccentColor` attribute or [setAccentColor].
 */
class TemperatureGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val startAngle = 135f
    private val sweepAngle = 270f

    /** 0f..1f fraction of the range the current temperature represents. */
    private var progress = 0f

    private var accentColor = ContextCompat.getColor(context, R.color.hn_primary_cyan)
    private var trackColor = ContextCompat.getColor(context, R.color.hn_border_subtle)
    private var strokeWidthPx = dp(8f)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    init {
        attrs?.let { set ->
            val a = context.obtainStyledAttributes(set, R.styleable.TemperatureGaugeView)
            accentColor = a.getColor(R.styleable.TemperatureGaugeView_gaugeAccentColor, accentColor)
            trackColor = a.getColor(R.styleable.TemperatureGaugeView_gaugeTrackColor, trackColor)
            strokeWidthPx =
                a.getDimension(R.styleable.TemperatureGaugeView_gaugeStrokeWidth, strokeWidthPx)
            a.recycle()
        }
        applyPaints()
    }

    /** Accent (progress) color, e.g. cyan for driver, amber for passenger. */
    fun setAccentColor(color: Int) {
        accentColor = color
        applyPaints()
        invalidate()
    }

    /** Fraction of the temperature range currently reached, 0f..1f. */
    fun setProgress(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped != progress) {
            progress = clamped
            invalidate()
        }
    }

    private fun applyPaints() {
        trackPaint.color = trackColor
        trackPaint.strokeWidth = strokeWidthPx
        progressPaint.color = accentColor
        progressPaint.strokeWidth = strokeWidthPx
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = strokeWidthPx / 2f + dp(2f)
        val diameter = min(width, height).toFloat() - 2f * inset
        if (diameter <= 0f) return
        val left = (width - diameter) / 2f
        val top = (height - diameter) / 2f
        oval.set(left, top, left + diameter, top + diameter)

        canvas.drawArc(oval, startAngle, sweepAngle, false, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(oval, startAngle, sweepAngle * progress, false, progressPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
