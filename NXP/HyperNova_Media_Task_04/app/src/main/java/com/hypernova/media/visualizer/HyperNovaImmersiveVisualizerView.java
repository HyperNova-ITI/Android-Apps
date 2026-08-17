package com.hypernova.media.visualizer;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Random;

/**
 * Procedural HyperNova cockpit ambient visual.
 *
 * Dark mode:
 *   Bright cyan graphics over the dark HyperNova surface.
 *
 * Light mode:
 *   Deeper automotive cyan with higher contrast so the animation,
 *   particles and perspective road remain clearly visible.
 *
 * Intentionally disabled:
 *   - cockpit / sine-like line
 *   - lower ambient arc lines
 *
 * Intentionally enabled:
 *   - perspective road grid
 *   - particles
 *   - animated center/source motif
 */
public final class HyperNovaImmersiveVisualizerView extends View
        implements Choreographer.FrameCallback {

    private static final int PARTICLE_COUNT = 38;

    private final Paint paint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint softPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path cockpit =
            new Path();

    private final Path waveform =
            new Path();

    private final float[] particleX =
            new float[PARTICLE_COUNT];

    private final float[] particleY =
            new float[PARTICLE_COUNT];

    private final float[] particleSpeed =
            new float[PARTICLE_COUNT];

    private final float[] particleAlpha =
            new float[PARTICLE_COUNT];

    private final Choreographer choreographer =
            Choreographer.getInstance();

    private VisualizerState state =
            new VisualizerState(
                    VisualizerMode.IDLE,
                    0f,
                    true);

    private boolean running;

    private long lastFrameNanos;

    private float phase;

    private RadialGradient cyanGlow;

    private RadialGradient violetGlow;

    private LinearGradient horizonGradient;

    public HyperNovaImmersiveVisualizerView(
            Context context) {

        this(context, null);
    }

    public HyperNovaImmersiveVisualizerView(
            Context context,
            @Nullable AttributeSet attrs) {

        super(context, attrs);

        setLayerType(
                LAYER_TYPE_HARDWARE,
                null);

        Random random =
                new Random(0x48F1A2L);

        for (int i = 0;
             i < PARTICLE_COUNT;
             i++) {

            particleX[i] =
                    random.nextFloat();

            particleY[i] =
                    random.nextFloat();

            particleSpeed[i] =
                    0.012f
                            + random.nextFloat()
                            * 0.025f;

            particleAlpha[i] =
                    0.2f
                            + random.nextFloat()
                            * 0.65f;
        }
    }

    public void setVisualizerState(
            VisualizerState value) {

        state = value;

        invalidate();
    }

    public void start() {

        if (running) {
            return;
        }

        running =
                animationsEnabled();

        if (running) {

            lastFrameNanos = 0L;

            choreographer.postFrameCallback(
                    this);

        } else {

            invalidate();
        }
    }

    public void stop() {

        running = false;

        choreographer.removeFrameCallback(
                this);
    }

    private boolean animationsEnabled() {

        try {

            return Settings.Global.getFloat(
                    getContext()
                            .getContentResolver(),
                    Settings.Global
                            .ANIMATOR_DURATION_SCALE,
                    1f) > 0f;

        } catch (Exception ignored) {

            return true;
        }
    }

    private boolean isNightMode() {

        int nightMode =
                getResources()
                        .getConfiguration()
                        .uiMode
                        & Configuration
                        .UI_MODE_NIGHT_MASK;

        return nightMode
                == Configuration
                .UI_MODE_NIGHT_YES;
    }

    /**
     * Cyan used for lines / rings.
     *
     * Dark:
     *   bright cyan.
     *
     * Light:
     *   deeper cyan to maintain contrast against the light hero.
     */
    private int cyan(
            int darkAlpha,
            int lightAlpha) {

        if (isNightMode()) {

            return Color.argb(
                    darkAlpha,
                    40,
                    232,
                    239);
        }

        return Color.argb(
                lightAlpha,
                0,
                112,
                124);
    }

    private int secondaryCyan(
            int darkAlpha,
            int lightAlpha) {

        if (isNightMode()) {

            return Color.argb(
                    darkAlpha,
                    55,
                    214,
                    226);
        }

        return Color.argb(
                lightAlpha,
                0,
                139,
                156);
    }

    @Override
    public void doFrame(
            long frameTimeNanos) {

        if (!running) {
            return;
        }

        float dt =
                lastFrameNanos == 0L
                        ? 0.016f
                        : Math.min(
                                0.05f,
                                (frameTimeNanos
                                        - lastFrameNanos)
                                        / 1_000_000_000f);

        lastFrameNanos =
                frameTimeNanos;

        float velocity =
                state.mode
                        == VisualizerMode.PAUSED
                        ? 0.18f
                        : isPlayingMode()
                                ? 1.15f
                                : 0.42f;

        phase =
                (phase
                        + dt * velocity)
                        % 1000f;

        for (int i = 0;
             i < PARTICLE_COUNT;
             i++) {

            particleY[i] -=
                    dt
                            * particleSpeed[i]
                            * velocity;

            if (particleY[i]
                    < -0.04f) {

                particleY[i] =
                        1.04f;
            }
        }

        invalidate();

        choreographer.postFrameCallback(
                this);
    }

    private boolean isPlayingMode() {

        return state.mode
                == VisualizerMode.RADIO_PLAYING

                || state.mode
                == VisualizerMode
                .LIBRARY_AUDIO_PLAYING

                || state.mode
                == VisualizerMode
                .BLUETOOTH_PLAYING;
    }

    @Override
    protected void onSizeChanged(
            int w,
            int h,
            int oldw,
            int oldh) {

        super.onSizeChanged(
                w,
                h,
                oldw,
                oldh);

        if (isNightMode()) {

            cyanGlow =
                    new RadialGradient(
                            w * 0.52f,
                            h * 0.64f,
                            w * 0.58f,
                            new int[] {
                                    0x7021DDEA,
                                    0x2218A9C1,
                                    Color.TRANSPARENT
                            },
                            null,
                            Shader.TileMode.CLAMP);

            violetGlow =
                    new RadialGradient(
                            w * 0.82f,
                            h * 0.18f,
                            w * 0.42f,
                            new int[] {
                                    0x36B14EEA,
                                    0x0DA65BE8,
                                    Color.TRANSPARENT
                            },
                            null,
                            Shader.TileMode.CLAMP);

        } else {

            /*
             * Stronger contrast for Light Mode.
             *
             * Still subtle enough to keep the premium clean appearance,
             * but no longer disappears into the white hero surface.
             */
            cyanGlow =
                    new RadialGradient(
                            w * 0.52f,
                            h * 0.60f,
                            w * 0.58f,
                            new int[] {
                                    0x30008D9C,
                                    0x1600B8C8,
                                    Color.TRANSPARENT
                            },
                            null,
                            Shader.TileMode.CLAMP);

            violetGlow =
                    new RadialGradient(
                            w * 0.82f,
                            h * 0.18f,
                            w * 0.42f,
                            new int[] {
                                    0x16874EC7,
                                    0x07874EC7,
                                    Color.TRANSPARENT
                            },
                            null,
                            Shader.TileMode.CLAMP);
        }

        horizonGradient =
                new LinearGradient(
                        0,
                        h * 0.45f,
                        w,
                        h * 0.45f,
                        new int[] {
                                Color.TRANSPARENT,
                                cyan(
                                        204,
                                        180),
                                Color.TRANSPARENT
                        },
                        null,
                        Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(
            Canvas canvas) {

        super.onDraw(
                canvas);

        int w =
                getWidth();

        int h =
                getHeight();

        if (w == 0
                || h == 0) {

            return;
        }

        /*
         * Ambient background glow.
         */
        paint.setShader(
                cyanGlow);

        canvas.drawRect(
                0,
                0,
                w,
                h,
                paint);

        paint.setShader(
                violetGlow);

        canvas.drawRect(
                0,
                0,
                w,
                h,
                paint);

        paint.setShader(
                null);

        /*
         * Keep the perspective road/grid.
         */
        drawDepthGrid(
                canvas,
                w,
                h);

        /*
         * Keep all animated elements.
         */
        drawParticles(
                canvas,
                w,
                h);

        drawStateMotif(
                canvas,
                w,
                h);
    }

    /**
     * Perspective road/grid.
     *
     * Light mode receives significantly more contrast than before.
     */
    private void drawDepthGrid(
            Canvas canvas,
            int w,
            int h) {

        softPaint.setStyle(
                Paint.Style.STROKE);

        softPaint.setStrokeWidth(
                isNightMode()
                        ? 1f
                        : 1.35f);

        softPaint.setColor(
                cyan(
                        24,
                        78));

        float horizon =
                h * 0.56f;

        for (int i = 1;
             i <= 6;
             i++) {

            float y =
                    horizon
                            + (h - horizon)
                            * i
                            * i
                            / 36f;

            canvas.drawLine(
                    0,
                    y,
                    w,
                    y,
                    softPaint);
        }

        for (int i = -4;
             i <= 4;
             i++) {

            canvas.drawLine(
                    w * 0.5f,
                    horizon,
                    w
                            * (0.5f
                            + i * 0.19f),
                    h,
                    softPaint);
        }
    }

    /**
     * Kept in source for possible future visual variants.
     *
     * Not called from onDraw().
     */
    private void drawCockpit(
            Canvas canvas,
            int w,
            int h) {

        cockpit.reset();

        cockpit.moveTo(
                0,
                h * 0.62f);

        cockpit.cubicTo(
                w * 0.16f,
                h * 0.5f,
                w * 0.28f,
                h * 0.47f,
                w * 0.42f,
                h * 0.55f);

        cockpit.cubicTo(
                w * 0.48f,
                h * 0.59f,
                w * 0.52f,
                h * 0.59f,
                w * 0.58f,
                h * 0.55f);

        cockpit.cubicTo(
                w * 0.72f,
                h * 0.47f,
                w * 0.84f,
                h * 0.5f,
                w,
                h * 0.62f);

        cockpit.lineTo(
                w,
                h);

        cockpit.lineTo(
                0,
                h);

        cockpit.close();

        paint.setStyle(
                Paint.Style.FILL);

        paint.setColor(
                0xB8040A12);

        canvas.drawPath(
                cockpit,
                paint);

        paint.setShader(
                horizonGradient);

        canvas.drawRect(
                0,
                h * 0.535f,
                w,
                h * 0.548f,
                paint);

        paint.setShader(
                null);

        paint.setStyle(
                Paint.Style.STROKE);

        paint.setStrokeWidth(
                Math.max(
                        2f,
                        w / 260f));

        paint.setColor(
                cyan(
                        102,
                        120));

        canvas.drawPath(
                cockpit,
                paint);
    }

    /**
     * Lower decorative arcs.
     *
     * Kept in source but intentionally not called from onDraw().
     */
    private void drawAmbientStrips(
            Canvas canvas,
            int w,
            int h) {

        float breath =
                0.58f
                        + 0.22f
                        * (float)
                        Math.sin(
                                phase * 2.1f);

        paint.setStyle(
                Paint.Style.STROKE);

        paint.setStrokeCap(
                Paint.Cap.ROUND);

        paint.setStrokeWidth(
                Math.max(
                        2f,
                        w / 180f));

        paint.setColor(
                cyan(
                        (int) (
                                145
                                        * breath),
                        (int) (
                                170
                                        * breath)));

        cockpit.reset();

        cockpit.moveTo(
                w * 0.08f,
                h * 0.68f);

        cockpit.cubicTo(
                w * 0.28f,
                h * 0.59f,
                w * 0.38f,
                h * 0.66f,
                w * 0.44f,
                h * 0.7f);

        canvas.drawPath(
                cockpit,
                paint);

        cockpit.reset();

        cockpit.moveTo(
                w * 0.92f,
                h * 0.68f);

        cockpit.cubicTo(
                w * 0.72f,
                h * 0.59f,
                w * 0.62f,
                h * 0.66f,
                w * 0.56f,
                h * 0.7f);

        canvas.drawPath(
                cockpit,
                paint);

        paint.setStrokeCap(
                Paint.Cap.BUTT);
    }

    private void drawParticles(
            Canvas canvas,
            int w,
            int h) {

        paint.setStyle(
                Paint.Style.FILL);

        float energy =
                isPlayingMode()
                        ? 1f
                        : 0.55f;

        float alphaScale =
                isNightMode()
                        ? 120f
                        : 205f;

        for (int i = 0;
             i < PARTICLE_COUNT;
             i++) {

            float drift =
                    (float)
                            Math.sin(
                                    phase
                                            + i
                                            * 1.71f)
                            * w
                            * 0.012f;

            int alpha =
                    Math.min(
                            255,
                            (int) (
                                    particleAlpha[i]
                                            * alphaScale
                                            * energy));

            if (isNightMode()) {

                paint.setColor(
                        Color.argb(
                                alpha,
                                i % 9 == 0
                                        ? 184
                                        : 71,
                                i % 9 == 0
                                        ? 88
                                        : 226,
                                238));

            } else {

                paint.setColor(
                        Color.argb(
                                alpha,
                                i % 9 == 0
                                        ? 135
                                        : 0,
                                i % 9 == 0
                                        ? 78
                                        : 130,
                                i % 9 == 0
                                        ? 185
                                        : 145));
            }

            canvas.drawCircle(
                    particleX[i]
                            * w
                            + drift,
                    particleY[i]
                            * h,
                    0.9f
                            + (i % 3)
                            * 0.7f,
                    paint);
        }
    }

    private void drawStateMotif(
            Canvas canvas,
            int w,
            int h) {

        float cx =
                w * 0.5f;

        float cy =
                h * 0.44f;

        if (state.mode
                == VisualizerMode
                .BLUETOOTH_CONNECTING

                || state.mode
                == VisualizerMode
                .BLUETOOTH_CONNECTED

                || state.mode
                == VisualizerMode
                .BLUETOOTH_PLAYING) {

            drawBluetoothNodes(
                    canvas,
                    w,
                    h,
                    cx,
                    cy);

            return;
        }

        if (state.mode
                == VisualizerMode
                .RADIO_BUFFERING

                || state.mode
                == VisualizerMode
                .RADIO_PLAYING) {

            drawRadioRings(
                    canvas,
                    w,
                    h,
                    cx,
                    cy);

            if (state.mode
                    == VisualizerMode
                    .RADIO_PLAYING) {

                drawBars(
                        canvas,
                        w,
                        h);
            }

            return;
        }

        if (state.mode
                == VisualizerMode
                .USB_NO_DEVICE

                || state.mode
                == VisualizerMode
                .USB_INSERTED

                || state.mode
                == VisualizerMode
                .USB_SCANNING

                || state.mode
                == VisualizerMode
                .USB_REMOVED) {

            drawUsbConnector(
                    canvas,
                    w,
                    h,
                    cx,
                    cy);

            return;
        }

        drawWaves(
                canvas,
                w,
                h,
                cx,
                cy);

        if (state.mode
                == VisualizerMode
                .LIBRARY_AUDIO_PLAYING) {

            drawBars(
                    canvas,
                    w,
                    h);
        }

        if (state.mode
                == VisualizerMode.ERROR) {

            paint.setStyle(
                    Paint.Style.STROKE);

            paint.setStrokeWidth(
                    3f);

            paint.setColor(
                    0xCCF2AB44);

            canvas.drawCircle(
                    cx,
                    cy,
                    w * 0.105f,
                    paint);
        }
    }

    private void drawUsbConnector(
            Canvas canvas,
            int w,
            int h,
            float cx,
            float cy) {

        boolean removed =
                state.mode
                        == VisualizerMode
                        .USB_REMOVED;

        boolean active =
                state.mode
                        == VisualizerMode
                        .USB_INSERTED

                || state.mode
                        == VisualizerMode
                        .USB_SCANNING;

        float glow =
                active
                        ? 0.75f
                        + 0.2f
                        * (float)
                        Math.sin(
                                phase * 3f)
                        : 0.35f;

        paint.setStyle(
                Paint.Style.STROKE);

        paint.setStrokeWidth(
                Math.max(
                        3f,
                        w / 150f));

        paint.setColor(
                cyan(
                        (int) (
                                210
                                        * glow),
                        (int) (
                                240
                                        * glow)));

        float bodyW =
                w * .19f;

        float bodyH =
                w * .12f;

        float offset =
                removed
                        ? w * .055f
                        : 0f;

        canvas.drawRoundRect(
                cx
                        - bodyW / 2f
                        - offset,
                cy
                        - bodyH / 2f,
                cx
                        + bodyW / 2f
                        - offset,
                cy
                        + bodyH / 2f,
                w * .018f,
                w * .018f,
                paint);

        canvas.drawLine(
                cx
                        + bodyW / 2f
                        - offset,
                cy
                        - bodyH
                        * .22f,
                cx
                        + bodyW
                        * .72f
                        + offset,
                cy
                        - bodyH
                        * .22f,
                paint);

        canvas.drawLine(
                cx
                        + bodyW / 2f
                        - offset,
                cy
                        + bodyH
                        * .22f,
                cx
                        + bodyW
                        * .72f
                        + offset,
                cy
                        + bodyH
                        * .22f,
                paint);

        paint.setStyle(
                Paint.Style.FILL);

        canvas.drawCircle(
                cx
                        - bodyW
                        * .2f
                        - offset,
                cy,
                w * .012f,
                paint);

        if (state.mode
                == VisualizerMode
                .USB_SCANNING) {

            for (int i = 0;
                 i < 6;
                 i++) {

                float travel =
                        (phase
                                * .7f
                                + i
                                / 6f)
                                % 1f;

                paint.setColor(
                        cyan(
                                (int) (
                                        (1f
                                                - travel)
                                                * 180),
                                (int) (
                                        (1f
                                                - travel)
                                                * 220)));

                canvas.drawCircle(
                        cx
                                + (travel
                                - .5f)
                                * w
                                * .55f,
                        cy
                                - w
                                * .13f,
                        2f
                                + i % 2,
                        paint);
            }
        }
    }

    /**
     * Main animated center rings.
     */
    private void drawWaves(
            Canvas canvas,
            int w,
            int h,
            float cx,
            float cy) {

        paint.setStyle(
                Paint.Style.STROKE);

        paint.setStrokeWidth(
                isNightMode()
                        ? Math.max(
                                1.5f,
                                w / 300f)
                        : Math.max(
                                2.2f,
                                w / 250f));

        for (int i = 0;
             i < 4;
             i++) {

            float cycle =
                    (phase
                            * 0.45f
                            + i
                            * 0.25f)
                            % 1f;

            float radius =
                    w
                            * (0.05f
                            + cycle
                            * 0.2f);

            int alpha =
                    isNightMode()
                            ? (int) (
                                    (1f
                                            - cycle)
                                            * 95f)
                            : (int) (
                                    (1f
                                            - cycle)
                                            * 205f);

            paint.setColor(
                    cyan(
                            alpha,
                            alpha));

            canvas.drawCircle(
                    cx,
                    cy,
                    radius,
                    paint);
        }
    }

    private void drawRadioRings(
            Canvas canvas,
            int w,
            int h,
            float cx,
            float cy) {

        paint.setStyle(
                Paint.Style.STROKE);

        paint.setStrokeWidth(
                isNightMode()
                        ? Math.max(
                                2f,
                                w / 220f)
                        : Math.max(
                                2.5f,
                                w / 190f));

        for (int i = 0;
             i < 3;
             i++) {

            float pulse =
                    0.78f
                            + 0.12f
                            * (float)
                            Math.sin(
                                    phase
                                            * 3f
                                            + i);

            int alpha =
                    isNightMode()
                            ? 175
                            - i * 42
                            : 230
                            - i * 45;

            paint.setColor(
                    cyan(
                            alpha,
                            alpha));

            canvas.drawCircle(
                    cx,
                    cy,
                    w
                            * (0.06f
                            + i
                            * 0.045f)
                            * pulse,
                    paint);
        }

        if (state.mode
                == VisualizerMode
                .RADIO_BUFFERING) {

            float angle =
                    phase * 150f;

            paint.setColor(
                    cyan(
                            221,
                            245));

            paint.setStrokeWidth(
                    Math.max(
                            3f,
                            w / 140f));

            canvas.drawArc(
                    cx
                            - w
                            * .17f,
                    cy
                            - w
                            * .17f,
                    cx
                            + w
                            * .17f,
                    cy
                            + w
                            * .17f,
                    angle,
                    58f,
                    false,
                    paint);
        }
    }

    private void drawBluetoothNodes(
            Canvas canvas,
            int w,
            int h,
            float cx,
            float cy) {

        boolean connecting =
                state.mode
                        == VisualizerMode
                        .BLUETOOTH_CONNECTING;

        float gap =
                connecting
                        ? w
                        * (0.14f
                        + 0.05f
                        * (float)
                        Math.sin(
                                phase
                                        * 2.5f))
                        : w
                        * 0.12f;

        paint.setStyle(
                Paint.Style.STROKE);

        paint.setStrokeWidth(
                isNightMode()
                        ? Math.max(
                                2f,
                                w / 180f)
                        : Math.max(
                                2.5f,
                                w / 165f));

        paint.setColor(
                cyan(
                        204,
                        235));

        canvas.drawLine(
                cx - gap,
                cy,
                cx + gap,
                cy,
                paint);

        paint.setStyle(
                Paint.Style.FILL);

        canvas.drawCircle(
                cx - gap,
                cy,
                w * 0.034f,
                paint);

        canvas.drawCircle(
                cx + gap,
                cy,
                w * 0.034f,
                paint);

        paint.setStyle(
                Paint.Style.STROKE);

        paint.setColor(
                secondaryCyan(
                        85,
                        150));

        canvas.drawCircle(
                cx - gap,
                cy,
                w * 0.07f,
                paint);

        canvas.drawCircle(
                cx + gap,
                cy,
                w * 0.07f,
                paint);

        if (state.mode
                == VisualizerMode
                .BLUETOOTH_PLAYING) {

            drawBars(
                    canvas,
                    w,
                    h);
        }
    }

    private void drawBars(
            Canvas canvas,
            int w,
            int h) {

        float base =
                h * 0.85f;

        int count =
                31;

        float available =
                w * 0.76f;

        float barW =
                available
                        / count
                        * 0.44f;

        float start =
                (w - available)
                        / 2f;

        paint.setStyle(
                Paint.Style.FILL);

        for (int i = 0;
             i < count;
             i++) {

            float harmonic =
                    0.45f
                            + 0.55f
                            * Math.abs(
                            (float)
                                    Math.sin(
                                            phase
                                                    * 4.1f
                                                    + state.progress
                                                    * 6.283f
                                                    + i
                                                    * 0.63f));

            float envelope =
                    0.25f
                            + 0.75f
                            * (float)
                            Math.sin(
                                    Math.PI
                                            * i
                                            / (count - 1));

            float height =
                    h
                            * 0.14f
                            * harmonic
                            * envelope;

            int alpha =
                    isNightMode()
                            ? 85
                            + (int) (
                                    harmonic
                                            * 130)
                            : 120
                            + (int) (
                                    harmonic
                                            * 125);

            paint.setColor(
                    cyan(
                            alpha,
                            alpha));

            float x =
                    start
                            + i
                            * available
                            / count;

            canvas.drawRoundRect(
                    x,
                    base - height,
                    x + barW,
                    base,
                    barW,
                    barW,
                    paint);
        }
    }
}
