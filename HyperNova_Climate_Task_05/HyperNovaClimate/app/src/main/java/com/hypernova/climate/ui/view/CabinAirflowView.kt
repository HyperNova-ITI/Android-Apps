package com.hypernova.climate.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import com.hypernova.climate.model.AirflowMode
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Premium airflow visualization over the top-down car (README §16).
 *
 * Renders soft, semi-transparent particle streams — a bright white core fading
 * into a neon rim glow (cyan cooling / amber heating) — that diffuse and widen
 * as they travel, waver with gentle turbulence, and flicker subtly. Everything
 * is driven by live state:
 *  - airflow **mode** → which vents emit and the stream direction/shape,
 *  - **fan level** → speed, density, stream length and glow intensity,
 *  - **accent color** → cool vs warm rim,
 *  - **zone** → driver (left) and passenger (right) animate independently.
 *
 * Glow is drawn with additive blending so it reads as emitted light on the dark
 * cabin. Pauses when hidden; static fallback under reduced-motion.
 */
class CabinAirflowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class ZoneAirflow(
        val mode: AirflowMode?,
        val fanLevel: Int,
        val accentColor: Int,
        val active: Boolean
    ) {
        companion object {
            val INACTIVE = ZoneAirflow(null, 0, Color.TRANSPARENT, false)
        }
    }

    private enum class Side { DRIVER, PASSENGER }

    private class Particle(
        var t: Float,          // 0..1 progress along the stream
        val lane: Float,       // -1..1 lateral fan target (diffusion)
        val phase: Float,      // per-particle waver/flicker phase
        val sizeSeed: Float    // 0.8..1.2 size variance
    )

    private class Emitter(
        val sx: Float, val sy: Float,     // normalized source
        val dx: Float, val dy: Float,     // unit direction
        val length: Float,                // path length (normalized)
        val spread: Float,                // lateral fan at the end
        val waveAmp: Float,               // turbulence amplitude
        val waveFreq: Float,
        val speed: Float,                 // path fraction / second
        val baseSizeDp: Float,
        val glowAlpha: Float,             // 0..1
        val color: Int,
        val glowFilter: PorterDuffColorFilter,
        val particles: List<Particle>
    )

    private val emitters = mutableListOf<Emitter>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val dst = RectF()
    private val carRect = RectF()

    private val glowSprite: Bitmap by lazy { buildGlowSprite() }

    private var running = false
    private var lastFrameNanos = 0L
    private var startNanos = 0L

    private var driver = ZoneAirflow.INACTIVE
    private var passenger = ZoneAirflow.INACTIVE

    private val reducedMotion: Boolean
        get() = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f

    fun setAirflow(driver: ZoneAirflow, passenger: ZoneAirflow) {
        this.driver = driver
        this.passenger = passenger
        rebuild()
        maybeStart()
    }

    fun pause() {
        running = false
    }

    fun resume() {
        maybeStart()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeCarRect(w.toFloat(), h.toFloat())
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) maybeStart() else running = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (carRect.isEmpty || emitters.isEmpty()) return

        val now = System.nanoTime()
        if (startNanos == 0L) startNanos = now
        val dt = if (lastFrameNanos == 0L) 0f
        else ((now - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrameNanos = now
        val time = (now - startNanos) / 1_000_000_000f

        val animate = running && !reducedMotion
        if (animate) advance(dt)
        drawStreams(canvas, time)

        if (animate) postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ build

    private fun rebuild() {
        emitters.clear()
        addZone(driver, Side.DRIVER)
        addZone(passenger, Side.PASSENGER)
    }

    private fun addZone(zone: ZoneAirflow, side: Side) {
        if (!zone.active || zone.mode == null || zone.fanLevel <= 0) return
        val fan = zone.fanLevel.coerceIn(1, 6)
        // Fan scaling.
        val densityF = 0.6f + 0.16f * fan          // particle count
        val speedF = 0.55f + 0.14f * fan           // velocity
        val glowF = (0.35f + 0.12f * fan).coerceAtMost(1f)
        val turbF = 0.6f + 0.10f * fan             // turbulence

        // Two vents per seat: one by the door (outer), one by the IVI (inner).
        val seatX = if (side == Side.DRIVER) 0.33f else 0.67f
        val innerX = if (side == Side.DRIVER) 0.45f else 0.55f   // console / IVI side
        val outerX = if (side == Side.DRIVER) 0.22f else 0.78f   // door side

        // A stream from a source point toward a target, forming the flow.
        fun stream(
            sx: Float, sy: Float, tx: Float, ty: Float,
            spread: Float, sizeDp: Float, countBase: Int, speedBase: Float
        ) {
            val dx = tx - sx
            val dy = ty - sy
            val len = hypot(dx, dy).coerceAtLeast(0.0001f)
            val count = (countBase * densityF).toInt().coerceIn(6, 16)
            val parts = List(count) { i ->
                Particle(
                    t = i.toFloat() / count,
                    lane = Random.nextFloat() * 2f - 1f,
                    phase = Random.nextFloat() * 6.2832f,
                    sizeSeed = 0.85f + Random.nextFloat() * 0.35f
                )
            }
            emitters.add(
                Emitter(
                    sx, sy, dx / len, dy / len,
                    len, spread, 0.012f * turbF, 1.4f,
                    speedBase * speedF, sizeDp, glowF, zone.accentColor,
                    PorterDuffColorFilter(zone.accentColor, PorterDuff.Mode.SRC_IN), parts
                )
            )
        }

        when (zone.mode) {
            // Both vents flow down over the seat.
            AirflowMode.FACE, AirflowMode.FACE_AND_FEET -> {
                stream(outerX, 0.22f, seatX, 0.56f, 0.05f, 11f, 8, 0.30f)
                stream(innerX, 0.22f, seatX, 0.56f, 0.05f, 11f, 8, 0.30f)
            }
            // Feet: shorter, wider, settles over the seat base.
            AirflowMode.FEET -> {
                stream(outerX, 0.26f, seatX, 0.52f, 0.07f, 12f, 8, 0.22f)
                stream(innerX, 0.26f, seatX, 0.52f, 0.07f, 12f, 8, 0.22f)
            }
            // Windshield/defrost: both vents flow up onto the glass.
            AirflowMode.WINDSHIELD -> {
                stream(outerX, 0.22f, outerX, 0.08f, 0.04f, 10f, 8, 0.28f)
                stream(innerX, 0.22f, innerX, 0.08f, 0.04f, 10f, 8, 0.28f)
            }
            AirflowMode.FEET_AND_DEFROST -> {
                stream(outerX, 0.26f, seatX, 0.52f, 0.07f, 11f, 7, 0.22f)
                stream(innerX, 0.22f, innerX, 0.08f, 0.04f, 9f, 7, 0.28f)
            }
        }
    }

    /** Matches ImageView scaleType=fitStart: scale to fit, aligned top-left. */
    private fun computeCarRect(vw: Float, vh: Float) {
        if (vw <= 0f || vh <= 0f) return
        val viewAspect = vw / vh
        if (viewAspect > CAR_ASPECT) {
            // Height-limited: full height, aligned to the left.
            val w = vh * CAR_ASPECT
            carRect.set(0f, 0f, w, vh)
        } else {
            // Width-limited: full width, aligned to the top.
            val h = vw / CAR_ASPECT
            carRect.set(0f, 0f, vw, h)
        }
    }

    // --------------------------------------------------------------- animate

    private fun advance(dt: Float) {
        for (e in emitters) {
            for (p in e.particles) {
                p.t += e.speed * dt
                if (p.t > 1f) p.t -= 1f
            }
        }
    }

    private fun drawStreams(canvas: Canvas, time: Float) {
        val density = resources.displayMetrics.density
        for (e in emitters) {
            val perpX = -e.dy
            val perpY = e.dx
            for (p in e.particles) {
                val t = p.t
                // Opacity: quick rise near the vent, dissipates by ~80% of path.
                val rise = (t / 0.1f).coerceIn(0f, 1f)
                val fall = 1f - smooth(0.15f, 0.85f, t)
                var alpha = rise * fall
                if (alpha <= 0.02f) continue
                // Subtle brightness flicker.
                alpha *= 0.75f + 0.25f * sin(time * 6f + p.phase)

                // Lateral diffusion (fan out) + gentle sinusoidal turbulence.
                val fan = p.lane * e.spread * t
                val waver = e.waveAmp * t *
                    sin(TWO_PI * e.waveFreq * t + p.phase + time * 1.6f)
                val lateral = fan + waver

                val nx = e.sx + e.dx * e.length * t + perpX * lateral
                val ny = e.sy + e.dy * e.length * t + perpY * lateral
                val cx = px(nx)
                val cy = py(ny)

                // Size grows as it travels (narrow at source, soft downstream).
                val coreR = e.baseSizeDp * density * p.sizeSeed * (0.55f + 0.85f * t)

                // Glow (tinted rim, additive bloom).
                val glowR = coreR * 2.3f
                paint.colorFilter = e.glowFilter
                paint.alpha = (alpha * e.glowAlpha * 150f).toInt().coerceIn(0, 255)
                blit(canvas, cx, cy, glowR)

                // White core.
                paint.colorFilter = null
                paint.alpha = (alpha * 235f).toInt().coerceIn(0, 255)
                blit(canvas, cx, cy, coreR)
            }
        }
    }

    private fun blit(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        dst.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawBitmap(glowSprite, null, dst, paint)
    }

    private fun maybeStart() {
        val shouldRun = emitters.isNotEmpty() && isShown
        if (shouldRun && !reducedMotion) {
            if (!running) {
                running = true
                lastFrameNanos = 0L
                postInvalidateOnAnimation()
            }
        } else {
            running = false
            invalidate() // static fallback / clear
        }
    }

    private fun buildGlowSprite(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            r, r, r,
            intArrayOf(
                Color.WHITE,
                Color.argb(150, 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(r, r, r, p)
        return bmp
    }

    private fun px(nx: Float) = carRect.left + nx * carRect.width()
    private fun py(ny: Float) = carRect.top + ny * carRect.height()

    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        // vehicle_top_dark.png is 891 x 1456 (car flush to the top edge).
        const val CAR_ASPECT = 891f / 1456f
        const val TWO_PI = 6.2831855f
    }
}
