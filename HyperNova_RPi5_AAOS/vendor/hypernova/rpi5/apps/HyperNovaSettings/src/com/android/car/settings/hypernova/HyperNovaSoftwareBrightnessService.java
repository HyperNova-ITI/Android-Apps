/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.car.settings.hypernova;

import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX;
import static com.android.settingslib.display.BrightnessUtils.convertLinearToGamma;

import android.app.Service;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * HDMI software-dimming fallback for the Raspberry Pi 5 cockpit display.
 *
 * <p>The audited ARZOPA HDMI panel exposes no entry under
 * {@code /sys/class/backlight}. Android can therefore store a brightness value
 * while the RPi Lights HAL has no physical node to drive. This service keeps the
 * Android setting as the source of truth and converts it into a non-interactive
 * black system overlay. It changes perceived luminance only; it does not claim
 * to reduce the monitor's LED backlight power.</p>
 *
 * <p>If a real backlight device becomes available on another target, the
 * software overlay stays transparent and the normal Lights HAL path remains the
 * sole brightness owner.</p>
 */
public final class HyperNovaSoftwareBrightnessService extends Service {
    private static final String TAG = "HyperNovaBrightness";
    private static final float MAX_DIM_ALPHA = 0.72f;

    private final Handler mHandler =
            new Handler(Looper.getMainLooper());

    private WindowManager mWindowManager;
    private PowerManager mPowerManager;

    @Nullable
    private View mDimView;

    private final ContentObserver mBrightnessObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange, @Nullable Uri uri) {
                    updateOverlay();
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();

        mWindowManager = getSystemService(WindowManager.class);
        mPowerManager = getSystemService(PowerManager.class);

        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                mBrightnessObserver,
                UserHandle.myUserId());

        ensureOverlay();
        updateOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureOverlay();
        updateOverlay();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        getContentResolver().unregisterContentObserver(mBrightnessObserver);

        if (mDimView != null && mWindowManager != null) {
            try {
                mWindowManager.removeView(mDimView);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to remove software dim overlay", exception);
            }
        }

        mDimView = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureOverlay() {
        if (mDimView != null || mWindowManager == null) {
            return;
        }

        View overlay = new View(this);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setAlpha(0f);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("HyperNova software display dimmer");
        try {
            mWindowManager.addView(overlay, params);
            mDimView = overlay;
            Log.i(TAG, "Software display dimmer ready");
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to create software display dimmer", exception);
        }
    }

    private void updateOverlay() {
        if (mDimView == null) {
            return;
        }

        if (hasPhysicalBacklight()) {
            mDimView.setAlpha(0f);
            return;
        }

        if (mPowerManager == null) {
            return;
        }

        int minimum = mPowerManager.getMinimumScreenBrightnessSetting();
        int maximum = mPowerManager.getMaximumScreenBrightnessSetting();

        int linear = Settings.System.getIntForUser(
                getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS,
                maximum,
                UserHandle.myUserId());

        linear = Math.max(minimum, Math.min(maximum, linear));

        int gamma = convertLinearToGamma(linear, minimum, maximum);
        gamma = Math.max(0, Math.min(GAMMA_SPACE_MAX, gamma));

        float visibleLevel = gamma / (float) GAMMA_SPACE_MAX;
        float alpha = MAX_DIM_ALPHA * (1f - visibleLevel);
        alpha = Math.max(0f, Math.min(MAX_DIM_ALPHA, alpha));

        mDimView.setAlpha(alpha);

        Log.d(TAG, "screenBrightness=" + linear + " dimAlpha=" + alpha);
    }

    private boolean hasPhysicalBacklight() {
        File directory = new File("/sys/class/backlight");
        File[] children = directory.listFiles();
        return children != null && children.length > 0;
    }
}
