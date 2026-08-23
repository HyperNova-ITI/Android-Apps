package com.hypernova.visuals

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Product-owned NOVA expression view shared by the Launcher and the NOVA app.
 *
 * The view deliberately draws its face instead of playing bitmap sequences: it stays sharp at
 * every AAOS density, does not allocate frames while running, and gives both APKs identical state
 * semantics. Call [setStateName] with the public runtime-state name and [setPalette] whenever the
 * day/night theme changes.
 */
class NovaFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private enum class FaceState {
        UNAVAILABLE,
        IDLE,
        LISTENING,
        PROCESSING,
        EXECUTING,
        SUCCESS,
        ERROR,
        SPEAKING,
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faceBounds = RectF()
    private var state = FaceState.UNAVAILABLE
    private var motion = 0f
    private var accent = Color.rgb(37, 217, 232)
    private var secondaryAccent = Color.rgb(168, 85, 247)
    private var success = Color.rgb(57, 234, 75)
    private var warning = Color.rgb(245, 166, 35)
    private var error = Color.rgb(255, 94, 104)
    private var animator: ValueAnimator? = null
    private var lastMotionFrame = -1

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setStateName(value: String?) {
        val next = runCatching {
            FaceState.valueOf(value.orEmpty().trim().uppercase())
        }.getOrDefault(FaceState.UNAVAILABLE)
        if (state == next) return
        state = next
        lastMotionFrame = -1
        invalidate()
    }

    fun setPalette(
        accent: Int,
        secondaryAccent: Int,
        success: Int,
        warning: Int,
        error: Int,
    ) {
        this.accent = accent
        this.secondaryAccent = secondaryAccent
        this.success = success
        this.warning = warning
        this.error = error
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMotionLifecycle()
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateMotionLifecycle()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateMotionLifecycle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        val size = min(availableWidth, availableHeight)
        if (size <= 0f) return

        val cx = paddingLeft + availableWidth / 2f
        val cy = paddingTop + availableHeight / 2f
        val radius = size * 0.43f
        val now = motion * CYCLE_MS
        val stateColor = stateColor()
        val active = state != FaceState.UNAVAILABLE
        val breathe = 0.5f + 0.5f * sin(now / CYCLE_MS * (2f * PI).toFloat())

        drawHalo(canvas, cx, cy, radius, stateColor, breathe, active)
        drawOrbit(canvas, cx, cy, radius, stateColor, now)
        drawVisor(canvas, cx, cy, radius, stateColor, active)
        drawExpression(canvas, cx, cy, radius, now, stateColor)
    }

    private fun drawHalo(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int,
        breathe: Float,
        active: Boolean,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(color, if (active) (18 + breathe * 22).toInt() else 8)
        canvas.drawCircle(cx, cy, radius * (1.12f + breathe * 0.035f), paint)
        paint.color = withAlpha(secondaryAccent, if (active) 14 else 5)
        canvas.drawCircle(cx + radius * 0.18f, cy - radius * 0.12f, radius * 0.82f, paint)
    }

    private fun drawOrbit(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int,
        now: Float,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = radius * 0.018f
        paint.color = withAlpha(color, if (state == FaceState.UNAVAILABLE) 45 else 105)
        canvas.drawCircle(cx, cy, radius, paint)

        val orbit = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.strokeWidth = radius * 0.045f
        paint.color = withAlpha(color, if (state == FaceState.UNAVAILABLE) 65 else 220)
        val start = when (state) {
            FaceState.PROCESSING -> (now / CYCLE_MS * 720f) - 90f
            FaceState.EXECUTING -> (now / CYCLE_MS * 480f) - 90f
            else -> -72f
        }
        val sweep = when (state) {
            FaceState.LISTENING -> 70f + 18f * sin(now * 0.006f)
            FaceState.SPEAKING -> 82f + 26f * abs(sin(now * 0.011f))
            FaceState.SUCCESS -> 210f
            FaceState.ERROR -> 44f
            FaceState.UNAVAILABLE -> 28f
            else -> 92f
        }
        canvas.drawArc(orbit, start, sweep, false, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (state == FaceState.UNAVAILABLE) withAlpha(warning, 90) else color
        val dotAngle = Math.toRadians((start + sweep).toDouble())
        canvas.drawCircle(
            cx + cos(dotAngle).toFloat() * radius,
            cy + sin(dotAngle).toFloat() * radius,
            radius * 0.052f,
            paint,
        )
    }

    private fun drawVisor(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int,
        active: Boolean,
    ) {
        val halfWidth = radius * 0.67f
        val halfHeight = radius * 0.43f
        faceBounds.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
        paint.style = Paint.Style.FILL
        paint.color = if (active) Color.rgb(4, 18, 30) else Color.rgb(15, 28, 38)
        canvas.drawRoundRect(faceBounds, radius * 0.24f, radius * 0.24f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.025f
        paint.color = withAlpha(color, if (active) 190 else 60)
        canvas.drawRoundRect(faceBounds, radius * 0.24f, radius * 0.24f, paint)
    }

    private fun drawExpression(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        now: Float,
        color: Int,
    ) {
        val eyeY = cy - radius * 0.10f
        val eyeDistance = radius * 0.28f
        val blink = when (state) {
            FaceState.IDLE, FaceState.SUCCESS -> idleBlink(now)
            FaceState.SPEAKING -> pulse(now % 3200f, 3050f, 95f)
            else -> 0f
        }
        val gaze = when (state) {
            FaceState.PROCESSING -> sin(now * 0.0048f) * radius * 0.055f
            FaceState.EXECUTING -> sin(now * 0.0024f) * radius * 0.025f
            else -> 0f
        }
        val eyeHeight = when (state) {
            FaceState.LISTENING -> radius * (0.18f + 0.018f * sin(now * 0.007f))
            FaceState.ERROR -> radius * 0.075f
            FaceState.UNAVAILABLE -> radius * 0.035f
            else -> radius * 0.14f
        } * (1f - blink * 0.88f)
        val eyeWidth = when (state) {
            FaceState.LISTENING -> radius * 0.12f
            FaceState.PROCESSING -> radius * 0.075f
            else -> radius * 0.095f
        }

        paint.style = Paint.Style.FILL
        paint.color = if (state == FaceState.UNAVAILABLE) withAlpha(color, 105) else color
        drawEye(canvas, cx - eyeDistance + gaze, eyeY, eyeWidth, eyeHeight, radius)
        paint.color = when (state) {
            FaceState.ERROR, FaceState.UNAVAILABLE -> withAlpha(color, 150)
            else -> secondaryAccent
        }
        drawEye(canvas, cx + eyeDistance + gaze, eyeY, eyeWidth, eyeHeight, radius)

        when (state) {
            FaceState.SPEAKING -> drawSpeechMouth(canvas, cx, cy + radius * 0.19f, radius, now, color)
            FaceState.PROCESSING -> drawThinkingMouth(canvas, cx, cy + radius * 0.20f, radius, now, color)
            FaceState.SUCCESS -> drawSmile(canvas, cx, cy + radius * 0.13f, radius, success)
            FaceState.ERROR -> drawErrorMouth(canvas, cx, cy + radius * 0.23f, radius)
            FaceState.LISTENING -> drawListeningMouth(canvas, cx, cy + radius * 0.19f, radius, color, now)
            FaceState.UNAVAILABLE -> drawSleepMouth(canvas, cx, cy + radius * 0.22f, radius)
            else -> drawNeutralMouth(canvas, cx, cy + radius * 0.21f, radius, color)
        }
    }

    private fun drawEye(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        radius: Float,
    ) {
        val bounds = RectF(cx - width, cy - height / 2f, cx + width, cy + height / 2f)
        canvas.drawRoundRect(bounds, radius * 0.06f, radius * 0.06f, paint)
    }

    private fun drawSpeechMouth(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        now: Float,
        color: Int,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        val barWidth = radius * 0.035f
        val spacing = radius * 0.085f
        for (index in -2..2) {
            val energy = 0.25f + 0.75f * abs(sin(now * 0.012f + index * 0.9f))
            val halfHeight = radius * (0.025f + 0.075f * energy)
            val x = cx + spacing * index
            canvas.drawRoundRect(
                RectF(x - barWidth, cy - halfHeight, x + barWidth, cy + halfHeight),
                barWidth,
                barWidth,
                paint,
            )
        }
    }

    private fun drawThinkingMouth(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        now: Float,
        color: Int,
    ) {
        paint.style = Paint.Style.FILL
        for (index in -1..1) {
            val activeDot = ((now / 360f).toInt() % 3) - 1
            paint.color = withAlpha(color, if (index == activeDot) 255 else 75)
            canvas.drawCircle(cx + index * radius * 0.13f, cy, radius * 0.035f, paint)
        }
    }

    private fun drawSmile(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = radius * 0.045f
        paint.color = color
        canvas.drawArc(
            RectF(cx - radius * 0.22f, cy - radius * 0.14f, cx + radius * 0.22f, cy + radius * 0.14f),
            18f,
            144f,
            false,
            paint,
        )
    }

    private fun drawErrorMouth(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = radius * 0.04f
        paint.color = error
        canvas.drawLine(cx - radius * 0.18f, cy, cx + radius * 0.18f, cy, paint)
    }

    private fun drawListeningMouth(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int,
        now: Float,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = radius * 0.035f
        paint.color = withAlpha(color, 180)
        val halfWidth = radius * (0.08f + 0.025f * (0.5f + 0.5f * sin(now * 0.007f)))
        canvas.drawLine(cx - halfWidth, cy, cx + halfWidth, cy, paint)
    }

    private fun drawSleepMouth(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = radius * 0.03f
        paint.color = withAlpha(warning, 90)
        canvas.drawLine(cx - radius * 0.13f, cy, cx + radius * 0.13f, cy, paint)
    }

    private fun drawNeutralMouth(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = radius * 0.035f
        paint.color = withAlpha(color, 165)
        canvas.drawLine(cx - radius * 0.12f, cy, cx + radius * 0.12f, cy, paint)
    }

    private fun stateColor(): Int = when (state) {
        FaceState.SUCCESS -> success
        FaceState.ERROR -> error
        FaceState.UNAVAILABLE -> warning
        else -> accent
    }

    private fun idleBlink(now: Float): Float {
        val cycle = now % CYCLE_MS
        return maxOf(
            pulse(cycle, 5050f, 95f),
            pulse(cycle, 5320f, 80f),
        )
    }

    private fun pulse(value: Float, center: Float, halfWidth: Float): Float =
        (1f - abs(value - center) / halfWidth).coerceIn(0f, 1f)

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun startMotion() {
        if (animator != null || !ValueAnimator.areAnimatorsEnabled()) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS.toLong()
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val nextMotion = it.animatedValue as Float
                val frameIntervalMs =
                    when (state) {
                        FaceState.LISTENING,
                        FaceState.PROCESSING,
                        FaceState.EXECUTING,
                        FaceState.SPEAKING,
                        -> ACTIVE_FRAME_INTERVAL_MS
                        FaceState.IDLE -> IDLE_FRAME_INTERVAL_MS
                        else -> QUIET_FRAME_INTERVAL_MS
                    }
                val frame = (nextMotion * CYCLE_MS / frameIntervalMs).toInt()
                if (frame != lastMotionFrame) {
                    lastMotionFrame = frame
                    motion = nextMotion
                    postInvalidateOnAnimation()
                }
            }
            start()
        }
    }

    /**
     * A HOME Activity remains attached while another cockpit task covers it. Continuing this
     * animator then invalidates an invisible software-rendered window and consumed a measurable
     * CPU core on the NXP guest. Animate only when this face can actually contribute a frame.
     */
    private fun updateMotionLifecycle() {
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            startMotion()
        } else {
            stopMotion()
        }
    }

    private fun stopMotion() {
        animator?.cancel()
        animator = null
        lastMotionFrame = -1
    }

    private companion object {
        const val CYCLE_MS = 6000f
        const val ACTIVE_FRAME_INTERVAL_MS = 33f
        const val IDLE_FRAME_INTERVAL_MS = 50f
        const val QUIET_FRAME_INTERVAL_MS = 100f
    }
}
