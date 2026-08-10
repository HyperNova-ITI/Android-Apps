package com.hypernova.media.visualizer;

import android.content.Context;
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

/** Procedural cockpit ambient visual. It reacts to player state, not microphone spectrum data. */
public final class HyperNovaImmersiveVisualizerView extends View
        implements Choreographer.FrameCallback {
    private static final int PARTICLE_COUNT = 38;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint softPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cockpit = new Path();
    private final Path waveform = new Path();
    private final float[] particleX = new float[PARTICLE_COUNT];
    private final float[] particleY = new float[PARTICLE_COUNT];
    private final float[] particleSpeed = new float[PARTICLE_COUNT];
    private final float[] particleAlpha = new float[PARTICLE_COUNT];
    private final Choreographer choreographer = Choreographer.getInstance();
    private VisualizerState state = new VisualizerState(VisualizerMode.IDLE, 0f, true);
    private boolean running;
    private long lastFrameNanos;
    private float phase;
    private RadialGradient cyanGlow;
    private RadialGradient violetGlow;
    private LinearGradient horizonGradient;

    public HyperNovaImmersiveVisualizerView(Context context) { this(context, null); }
    public HyperNovaImmersiveVisualizerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        Random random = new Random(0x48F1A2L);
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particleX[i] = random.nextFloat();
            particleY[i] = random.nextFloat();
            particleSpeed[i] = 0.012f + random.nextFloat() * 0.025f;
            particleAlpha[i] = 0.2f + random.nextFloat() * 0.65f;
        }
    }

    public void setVisualizerState(VisualizerState value) {
        state = value;
        invalidate();
    }

    public void start() {
        if (running) return;
        running = animationsEnabled();
        if (running) {
            lastFrameNanos = 0L;
            choreographer.postFrameCallback(this);
        } else invalidate();
    }

    public void stop() {
        running = false;
        choreographer.removeFrameCallback(this);
    }

    private boolean animationsEnabled() {
        try {
            return Settings.Global.getFloat(getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Exception ignored) { return true; }
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastFrameNanos == 0L ? 0.016f
                : Math.min(0.05f, (frameTimeNanos - lastFrameNanos) / 1_000_000_000f);
        lastFrameNanos = frameTimeNanos;
        float velocity = state.mode == VisualizerMode.PAUSED ? 0.18f
                : isPlayingMode() ? 1.15f : 0.42f;
        phase = (phase + dt * velocity) % 1000f;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particleY[i] -= dt * particleSpeed[i] * velocity;
            if (particleY[i] < -0.04f) particleY[i] = 1.04f;
        }
        invalidate();
        choreographer.postFrameCallback(this);
    }

    private boolean isPlayingMode() {
        return state.mode == VisualizerMode.RADIO_PLAYING
                || state.mode == VisualizerMode.LIBRARY_AUDIO_PLAYING
                || state.mode == VisualizerMode.BLUETOOTH_PLAYING;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cyanGlow = new RadialGradient(w * 0.52f, h * 0.64f, w * 0.58f,
                new int[]{0x7021DDEA, 0x2218A9C1, Color.TRANSPARENT}, null,
                Shader.TileMode.CLAMP);
        violetGlow = new RadialGradient(w * 0.82f, h * 0.18f, w * 0.42f,
                new int[]{0x36B14EEA, 0x0DA65BE8, Color.TRANSPARENT}, null,
                Shader.TileMode.CLAMP);
        horizonGradient = new LinearGradient(0, h * 0.45f, w, h * 0.45f,
                new int[]{Color.TRANSPARENT, 0xCC28E8EF, Color.TRANSPARENT}, null,
                Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        paint.setShader(cyanGlow);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(violetGlow);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        drawDepthGrid(canvas, w, h);
        drawCockpit(canvas, w, h);
        drawAmbientStrips(canvas, w, h);
        drawParticles(canvas, w, h);
        drawStateMotif(canvas, w, h);
    }

    private void drawDepthGrid(Canvas canvas, int w, int h) {
        softPaint.setStyle(Paint.Style.STROKE);
        softPaint.setStrokeWidth(1f);
        softPaint.setColor(0x1838D9E7);
        float horizon = h * 0.56f;
        for (int i = 1; i <= 6; i++) {
            float y = horizon + (h - horizon) * i * i / 36f;
            canvas.drawLine(0, y, w, y, softPaint);
        }
        for (int i = -4; i <= 4; i++) {
            canvas.drawLine(w * 0.5f, horizon, w * (0.5f + i * 0.19f), h, softPaint);
        }
    }

    private void drawCockpit(Canvas canvas, int w, int h) {
        cockpit.reset();
        cockpit.moveTo(0, h * 0.62f);
        cockpit.cubicTo(w * 0.16f, h * 0.5f, w * 0.28f, h * 0.47f, w * 0.42f, h * 0.55f);
        cockpit.cubicTo(w * 0.48f, h * 0.59f, w * 0.52f, h * 0.59f, w * 0.58f, h * 0.55f);
        cockpit.cubicTo(w * 0.72f, h * 0.47f, w * 0.84f, h * 0.5f, w, h * 0.62f);
        cockpit.lineTo(w, h);
        cockpit.lineTo(0, h);
        cockpit.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB8040A12);
        canvas.drawPath(cockpit, paint);

        paint.setShader(horizonGradient);
        canvas.drawRect(0, h * 0.535f, w, h * 0.548f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, w / 260f));
        paint.setColor(0x6639EAF0);
        canvas.drawPath(cockpit, paint);
    }

    private void drawAmbientStrips(Canvas canvas, int w, int h) {
        float breath = 0.58f + 0.22f * (float) Math.sin(phase * 2.1f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(2f, w / 180f));
        paint.setColor(Color.argb((int) (145 * breath), 41, 235, 242));
        cockpit.reset();
        cockpit.moveTo(w * 0.08f, h * 0.68f);
        cockpit.cubicTo(w * 0.28f, h * 0.59f, w * 0.38f, h * 0.66f, w * 0.44f, h * 0.7f);
        canvas.drawPath(cockpit, paint);
        cockpit.reset();
        cockpit.moveTo(w * 0.92f, h * 0.68f);
        cockpit.cubicTo(w * 0.72f, h * 0.59f, w * 0.62f, h * 0.66f, w * 0.56f, h * 0.7f);
        canvas.drawPath(cockpit, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawParticles(Canvas canvas, int w, int h) {
        paint.setStyle(Paint.Style.FILL);
        float energy = isPlayingMode() ? 1f : 0.55f;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            float drift = (float) Math.sin(phase + i * 1.71f) * w * 0.012f;
            paint.setColor(Color.argb((int) (particleAlpha[i] * 120f * energy),
                    i % 9 == 0 ? 184 : 71, i % 9 == 0 ? 88 : 226, 238));
            canvas.drawCircle(particleX[i] * w + drift, particleY[i] * h,
                    0.8f + (i % 3) * 0.6f, paint);
        }
    }

    private void drawStateMotif(Canvas canvas, int w, int h) {
        float cx = w * 0.5f;
        float cy = h * 0.44f;
        if (state.mode == VisualizerMode.BLUETOOTH_CONNECTING
                || state.mode == VisualizerMode.BLUETOOTH_CONNECTED
                || state.mode == VisualizerMode.BLUETOOTH_PLAYING) {
            drawBluetoothNodes(canvas, w, h, cx, cy);
            return;
        }
        if (state.mode == VisualizerMode.RADIO_BUFFERING
                || state.mode == VisualizerMode.RADIO_PLAYING) {
            drawRadioRings(canvas, w, h, cx, cy);
            if (state.mode == VisualizerMode.RADIO_PLAYING) drawBars(canvas, w, h);
            return;
        }
        if (state.mode == VisualizerMode.USB_NO_DEVICE
                || state.mode == VisualizerMode.USB_INSERTED
                || state.mode == VisualizerMode.USB_SCANNING
                || state.mode == VisualizerMode.USB_REMOVED) {
            drawUsbConnector(canvas, w, h, cx, cy);
            return;
        }
        drawWaves(canvas, w, h, cx, cy);
        if (state.mode == VisualizerMode.LIBRARY_AUDIO_PLAYING) drawBars(canvas, w, h);
        if (state.mode == VisualizerMode.ERROR) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            paint.setColor(0xCCF2AB44);
            canvas.drawCircle(cx, cy, w * 0.105f, paint);
        }
    }

    private void drawUsbConnector(Canvas canvas, int w, int h, float cx, float cy) {
        boolean removed = state.mode == VisualizerMode.USB_REMOVED;
        boolean active = state.mode == VisualizerMode.USB_INSERTED
                || state.mode == VisualizerMode.USB_SCANNING;
        float glow = active ? 0.75f + 0.2f * (float) Math.sin(phase * 3f) : 0.35f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, w / 150f));
        paint.setColor(Color.argb((int) (210 * glow), 38, 230, 239));
        float bodyW = w * .19f;
        float bodyH = w * .12f;
        float offset = removed ? w * .055f : 0f;
        canvas.drawRoundRect(cx - bodyW / 2f - offset, cy - bodyH / 2f,
                cx + bodyW / 2f - offset, cy + bodyH / 2f, w * .018f, w * .018f, paint);
        canvas.drawLine(cx + bodyW / 2f - offset, cy - bodyH * .22f,
                cx + bodyW * .72f + offset, cy - bodyH * .22f, paint);
        canvas.drawLine(cx + bodyW / 2f - offset, cy + bodyH * .22f,
                cx + bodyW * .72f + offset, cy + bodyH * .22f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx - bodyW * .2f - offset, cy, w * .012f, paint);
        if (state.mode == VisualizerMode.USB_SCANNING) {
            for (int i = 0; i < 6; i++) {
                float travel = (phase * .7f + i / 6f) % 1f;
                paint.setColor(Color.argb((int) ((1f - travel) * 180), 45, 232, 240));
                canvas.drawCircle(cx + (travel - .5f) * w * .55f, cy - w * .13f,
                        2f + i % 2, paint);
            }
        }
    }

    private void drawWaves(Canvas canvas, int w, int h, float cx, float cy) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, w / 300f));
        for (int i = 0; i < 4; i++) {
            float cycle = (phase * 0.45f + i * 0.25f) % 1f;
            float radius = w * (0.05f + cycle * 0.2f);
            paint.setColor(Color.argb((int) ((1f - cycle) * 95f), 37, 224, 237));
            canvas.drawCircle(cx, cy, radius, paint);
        }
    }

    private void drawRadioRings(Canvas canvas, int w, int h, float cx, float cy) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, w / 220f));
        for (int i = 0; i < 3; i++) {
            float pulse = 0.78f + 0.12f * (float) Math.sin(phase * 3f + i);
            paint.setColor(Color.argb(175 - i * 42, 35, 231, 239));
            canvas.drawCircle(cx, cy, w * (0.06f + i * 0.045f) * pulse, paint);
        }
        if (state.mode == VisualizerMode.RADIO_BUFFERING) {
            float angle = phase * 150f;
            paint.setColor(0xDD50F0F4);
            paint.setStrokeWidth(Math.max(3f, w / 140f));
            canvas.drawArc(cx - w * .17f, cy - w * .17f, cx + w * .17f, cy + w * .17f,
                    angle, 58f, false, paint);
        }
    }

    private void drawBluetoothNodes(Canvas canvas, int w, int h, float cx, float cy) {
        boolean connecting = state.mode == VisualizerMode.BLUETOOTH_CONNECTING;
        float gap = connecting ? w * (0.14f + 0.05f * (float) Math.sin(phase * 2.5f)) : w * 0.12f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, w / 180f));
        paint.setColor(0xCC28E8EF);
        canvas.drawLine(cx - gap, cy, cx + gap, cy, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx - gap, cy, w * 0.034f, paint);
        canvas.drawCircle(cx + gap, cy, w * 0.034f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0x553BEAF1);
        canvas.drawCircle(cx - gap, cy, w * 0.07f, paint);
        canvas.drawCircle(cx + gap, cy, w * 0.07f, paint);
        if (state.mode == VisualizerMode.BLUETOOTH_PLAYING) drawBars(canvas, w, h);
    }

    private void drawBars(Canvas canvas, int w, int h) {
        float base = h * 0.85f;
        int count = 31;
        float available = w * 0.76f;
        float barW = available / count * 0.44f;
        float start = (w - available) / 2f;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < count; i++) {
            float harmonic = 0.45f + 0.55f * Math.abs((float) Math.sin(
                    phase * 4.1f + state.progress * 6.283f + i * 0.63f));
            float envelope = 0.25f + 0.75f * (float) Math.sin(Math.PI * i / (count - 1));
            float height = h * 0.14f * harmonic * envelope;
            paint.setColor(Color.argb(85 + (int) (harmonic * 130), 29, 223, 237));
            float x = start + i * available / count;
            canvas.drawRoundRect(x, base - height, x + barW, base, barW, barW, paint);
        }
    }
}
