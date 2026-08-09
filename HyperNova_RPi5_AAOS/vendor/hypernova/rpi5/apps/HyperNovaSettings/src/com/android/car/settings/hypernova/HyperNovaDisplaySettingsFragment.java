/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.car.settings.hypernova;

import static android.car.settings.CarSettings.Global.FORCED_DAY_NIGHT_MODE;
import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX;
import static com.android.settingslib.display.BrightnessUtils.convertGammaToLinear;
import static com.android.settingslib.display.BrightnessUtils.convertLinearToGamma;

import android.app.Dialog;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.hypernova.settings.R;
import com.android.car.settings.common.BaseFragment;

/**
 * HyperNova Display screen.
 *
 * The UI is app-owned and full-width while all display values
 * continue to use Android platform settings.
 */
public final class HyperNovaDisplaySettingsFragment extends BaseFragment {

    private static final int MODE_AUTO = 0;
    private static final int MODE_LIGHT = 1;
    private static final int MODE_DARK = 2;

    private static final Uri BRIGHTNESS_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);

    private static final Uri BRIGHTNESS_MODE_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE);

    private static final Uri DAY_NIGHT_URI =
            Settings.Global.getUriFor(FORCED_DAY_NIGHT_MODE);

    private SeekBar mBrightnessSeekBar;
    private TextView mBrightnessValue;
    private TextView mBrightnessSubValue;

    private View mAutoBrightnessCard;
    private Switch mAutoBrightnessSwitch;
    private TextView mAutoBrightnessSummary;

    private TextView mDayNightSummary;
    private TextView mThemeStyleSummary;

    private PowerManager mPowerManager;

    private boolean mRefreshing;

    private ContentObserver mSettingsObserver;

    private interface ChoiceHandler {
        void onChoice(int index);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.hypernova_display_fragment;
    }

    @Override
    @StringRes
    protected int getTitleId() {
        return R.string.hypernova_display;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPowerManager =
                requireContext().getSystemService(PowerManager.class);

        mSettingsObserver =
                new ContentObserver(new Handler(Looper.getMainLooper())) {

                    @Override
                    public void onChange(boolean selfChange) {
                        refreshAll();
                    }
                };
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            Bundle savedInstanceState) {

        super.onViewCreated(view, savedInstanceState);

        HyperNovaSettingsActivities.configureHeader(
                this,
                R.string.hypernova_display,
                /* showBack= */ true);

        bindBrightness(view);

        bindAutomaticBrightness(view);

        bindTheme(view);

        refreshAll();
    }

    @Override
    public void onStart() {
        super.onStart();

        requireContext()
                .getContentResolver()
                .registerContentObserver(
                        BRIGHTNESS_URI,
                        false,
                        mSettingsObserver);

        requireContext()
                .getContentResolver()
                .registerContentObserver(
                        BRIGHTNESS_MODE_URI,
                        false,
                        mSettingsObserver);

        requireContext()
                .getContentResolver()
                .registerContentObserver(
                        DAY_NIGHT_URI,
                        false,
                        mSettingsObserver);

        refreshAll();
    }

    @Override
    public void onStop() {

        requireContext()
                .getContentResolver()
                .unregisterContentObserver(
                        mSettingsObserver);

        super.onStop();
    }

    // ============================================================
    // Brightness
    // ============================================================

    private void bindBrightness(View root) {

        mBrightnessSeekBar =
                root.findViewById(
                        R.id.hypernova_brightness_seekbar);

        mBrightnessValue =
                root.findViewById(
                        R.id.hypernova_brightness_value);

        mBrightnessSubValue =
                root.findViewById(
                        R.id.hypernova_brightness_sub_value);

        View decrease =
                root.findViewById(
                        R.id.hypernova_brightness_decrease);

        View increase =
                root.findViewById(
                        R.id.hypernova_brightness_increase);

        mBrightnessSeekBar.setMax(GAMMA_SPACE_MAX);

        mBrightnessSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser) {

                        updateBrightnessLabels(progress);

                        if (!fromUser || mRefreshing) {
                            return;
                        }

                        writeBrightness(progress);
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar) {
                    }
                });

        decrease.setOnClickListener(
                view -> adjustBrightness(
                        -Math.max(
                                1,
                                GAMMA_SPACE_MAX / 20)));

        increase.setOnClickListener(
                view -> adjustBrightness(
                        Math.max(
                                1,
                                GAMMA_SPACE_MAX / 20)));
    }

    // ============================================================
    // Automatic Brightness
    // ============================================================

    private void bindAutomaticBrightness(View root) {

        mAutoBrightnessCard =
                root.findViewById(
                        R.id.hypernova_auto_brightness_card);

        mAutoBrightnessSwitch =
                root.findViewById(
                        R.id.hypernova_auto_brightness_switch);

        mAutoBrightnessSummary =
                root.findViewById(
                        R.id.hypernova_auto_brightness_summary);

        mAutoBrightnessSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {

                    if (mRefreshing) {
                        return;
                    }

                    setAutomaticBrightness(isChecked);
                });

        mAutoBrightnessCard.setOnClickListener(view -> {

            if (!mRefreshing && mAutoBrightnessSwitch.isEnabled()) {

                mAutoBrightnessSwitch.setChecked(
                        !mAutoBrightnessSwitch.isChecked());
            }
        });
    }

    // ============================================================
    // Theme
    // ============================================================

    private void bindTheme(View root) {

        View dayNightRow =
                root.findViewById(
                        R.id.hypernova_day_night_row);

        View themeStyleRow =
                root.findViewById(
                        R.id.hypernova_theme_style_row);

        mDayNightSummary =
                root.findViewById(
                        R.id.hypernova_day_night_summary);

        mThemeStyleSummary =
                root.findViewById(
                        R.id.hypernova_theme_style_summary);

        dayNightRow.setOnClickListener(
                view -> showDayNightDialog());

        themeStyleRow.setOnClickListener(
                view -> showThemeStyleDialog());
    }

    // ============================================================
    // Refresh
    // ============================================================

    private void refreshAll() {

        if (!isAdded()
                || mBrightnessSeekBar == null
                || mAutoBrightnessSwitch == null) {

            return;
        }

        mRefreshing = true;

        refreshBrightness();

        refreshAutomaticBrightness();

        refreshTheme();

        mRefreshing = false;
    }

    private void refreshBrightness() {

        int minimum =
                mPowerManager
                        .getMinimumScreenBrightnessSetting();

        int maximum =
                mPowerManager
                        .getMaximumScreenBrightnessSetting();

        int linear =
                Settings.System.getIntForUser(
                        requireContext().getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS,
                        maximum,
                        UserHandle.myUserId());

        linear =
                Math.max(
                        minimum,
                        Math.min(maximum, linear));

        int gamma =
                convertLinearToGamma(
                        linear,
                        minimum,
                        maximum);

        gamma =
                Math.max(
                        0,
                        Math.min(
                                GAMMA_SPACE_MAX,
                                gamma));

        mBrightnessSeekBar.setProgress(gamma);

        updateBrightnessLabels(gamma);
    }

    private void updateBrightnessLabels(int gamma) {

        int percentage =
                Math.round(
                        gamma * 100f
                                / GAMMA_SPACE_MAX);

        String value =
                getString(
                        R.string.hypernova_percent,
                        percentage);

        mBrightnessValue.setText(value);

        mBrightnessSubValue.setText(
                R.string.hypernova_brightness_fallback_summary);
    }

    private void writeBrightness(int gamma) {

        int minimum =
                mPowerManager
                        .getMinimumScreenBrightnessSetting();

        int maximum =
                mPowerManager
                        .getMaximumScreenBrightnessSetting();

        int linear =
                convertGammaToLinear(
                        gamma,
                        minimum,
                        maximum);

        Settings.System.putIntForUser(
                requireContext().getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS,
                linear,
                UserHandle.myUserId());
    }

    private void adjustBrightness(int delta) {

        int current =
                mBrightnessSeekBar.getProgress();

        int next =
                Math.max(
                        0,
                        Math.min(
                                GAMMA_SPACE_MAX,
                                current + delta));

        if (next == current) {
            return;
        }

        mBrightnessSeekBar.setProgress(next);

        writeBrightness(next);
    }

    // ============================================================
    // Automatic brightness
    // ============================================================

    private void refreshAutomaticBrightness() {

        if (!isAutomaticBrightnessSupported()) {
            mAutoBrightnessSwitch.setChecked(false);
            mAutoBrightnessSwitch.setEnabled(false);
            mAutoBrightnessCard.setClickable(false);
            mAutoBrightnessCard.setAlpha(0.55f);
            mAutoBrightnessSummary.setText(
                    R.string.hypernova_state_unavailable);
            return;
        }

        mAutoBrightnessSwitch.setEnabled(true);
        mAutoBrightnessCard.setClickable(true);
        mAutoBrightnessCard.setAlpha(1.0f);

        int mode =
                Settings.System.getIntForUser(
                        requireContext().getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System
                                .SCREEN_BRIGHTNESS_MODE_MANUAL,
                        UserHandle.myUserId());

        boolean automatic =
                mode
                        == Settings.System
                        .SCREEN_BRIGHTNESS_MODE_AUTOMATIC;

        mAutoBrightnessSwitch.setChecked(automatic);

        mAutoBrightnessSummary.setText(
                automatic
                        ? R.string.hypernova_state_on
                        : R.string.hypernova_state_off);
    }

    private boolean isAutomaticBrightnessSupported() {
        SensorManager sensorManager =
                requireContext().getSystemService(SensorManager.class);

        return sensorManager != null
                && sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
    }

    private void setAutomaticBrightness(boolean enabled) {

        int mode =
                enabled
                        ? Settings.System
                        .SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        : Settings.System
                        .SCREEN_BRIGHTNESS_MODE_MANUAL;

        Settings.System.putIntForUser(
                requireContext().getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                mode,
                UserHandle.myUserId());

        mAutoBrightnessSummary.setText(
                enabled
                        ? R.string.hypernova_state_on
                        : R.string.hypernova_state_off);
    }

    // ============================================================
    // Theme state
    // ============================================================

    private int getDayNightMode() {

        return Settings.Global.getInt(
                requireContext().getContentResolver(),
                FORCED_DAY_NIGHT_MODE,
                MODE_AUTO);
    }

    private void refreshTheme() {

        int mode =
                getDayNightMode();

        switch (mode) {

            case MODE_LIGHT:

                mDayNightSummary.setText(
                        R.string.hypernova_theme_light);

                mThemeStyleSummary.setText(
                        R.string.hypernova_theme_style_light);

                break;


            case MODE_DARK:

                mDayNightSummary.setText(
                        R.string.hypernova_theme_dark);

                mThemeStyleSummary.setText(
                        R.string.hypernova_theme_style_dark);

                break;


            case MODE_AUTO:
            default:

                mDayNightSummary.setText(
                        R.string.hypernova_theme_auto_short);

                updateAutomaticThemeStyle();

                break;
        }
    }

    private void updateAutomaticThemeStyle() {

        int nightMode =
                getResources()
                        .getConfiguration()
                        .uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;

        if (nightMode
                == Configuration.UI_MODE_NIGHT_YES) {

            mThemeStyleSummary.setText(
                    R.string.hypernova_theme_style_dark);

        } else {

            mThemeStyleSummary.setText(
                    R.string.hypernova_theme_style_light);
        }
    }

    // ============================================================
    // HyperNova custom dialogs
    // ============================================================

    private void showDayNightDialog() {

        String[] titles = {
                getString(
                        R.string.hypernova_theme_auto_short),

                getString(
                        R.string.hypernova_theme_light),

                getString(
                        R.string.hypernova_theme_dark)
        };

        String[] summaries = {
                "Follow vehicle and system day/night mode",

                "Always use the HyperNova light appearance",

                "Always use the HyperNova dark appearance"
        };

        int current =
                Math.max(
                        MODE_AUTO,
                        Math.min(
                                MODE_DARK,
                                getDayNightMode()));

        showChoiceDialog(
                "Day / Night Mode",
                "Choose the cockpit appearance mode",
                titles,
                summaries,
                current,
                this::setDayNightMode);
    }

    private void showThemeStyleDialog() {

        String[] titles = {
                getString(
                        R.string.hypernova_theme_style_light),

                getString(
                        R.string.hypernova_theme_style_dark)
        };

        String[] summaries = {
                "Bright HyperNova cockpit appearance",

                "Dark HyperNova cockpit appearance"
        };

        int currentMode =
                getDayNightMode();

        int selected;

        if (currentMode == MODE_LIGHT) {

            selected = 0;

        } else if (currentMode == MODE_DARK) {

            selected = 1;

        } else {

            int nightMode =
                    getResources()
                            .getConfiguration()
                            .uiMode
                            & Configuration.UI_MODE_NIGHT_MASK;

            selected =
                    nightMode
                            == Configuration.UI_MODE_NIGHT_YES
                            ? 1
                            : 0;
        }

        showChoiceDialog(
                "Theme Style",
                "Choose the HyperNova visual style",
                titles,
                summaries,
                selected,
                index -> setDayNightMode(
                        index == 0
                                ? MODE_LIGHT
                                : MODE_DARK));
    }

    private void showChoiceDialog(
            CharSequence title,
            CharSequence subtitle,
            String[] optionTitles,
            String[] optionSummaries,
            int selectedIndex,
            ChoiceHandler handler) {

        Dialog dialog =
                new Dialog(requireContext());

        dialog.requestWindowFeature(
                Window.FEATURE_NO_TITLE);

        dialog.setContentView(
                R.layout.hypernova_choice_dialog);

        TextView titleView =
                dialog.findViewById(
                        R.id.hypernova_choice_dialog_title);

        TextView subtitleView =
                dialog.findViewById(
                        R.id.hypernova_choice_dialog_subtitle);

        titleView.setText(title);

        subtitleView.setText(subtitle);

        int[] rowIds = {
                R.id.hypernova_choice_option_1,
                R.id.hypernova_choice_option_2,
                R.id.hypernova_choice_option_3
        };

        for (int index = 0;
                index < rowIds.length;
                index++) {

            View row =
                    dialog.findViewById(
                            rowIds[index]);

            if (index >= optionTitles.length) {

                row.setVisibility(View.GONE);

                continue;
            }

            row.setVisibility(View.VISIBLE);

            final int choiceIndex = index;

            configureChoiceRow(
                    row,
                    optionTitles[index],
                    optionSummaries[index],
                    index == selectedIndex,
                    view -> {

                        handler.onChoice(
                                choiceIndex);

                        dialog.dismiss();
                    });
        }

        View cancel =
                dialog.findViewById(
                        R.id.hypernova_choice_cancel);

        cancel.setOnClickListener(
                view -> dialog.dismiss());

        dialog.setCanceledOnTouchOutside(true);

        dialog.show();

        configureDialogWindow(dialog);
    }

    private void configureChoiceRow(
            View row,
            CharSequence title,
            CharSequence summary,
            boolean selected,
            View.OnClickListener listener) {

        TextView titleView =
                row.findViewById(
                        R.id.hypernova_choice_option_title);

        TextView summaryView =
                row.findViewById(
                        R.id.hypernova_choice_option_summary);

        ImageView indicator =
                row.findViewById(
                        R.id.hypernova_choice_indicator);

        titleView.setText(title);

        summaryView.setText(summary);

        row.setBackgroundResource(
                selected
                        ? R.drawable
                        .hypernova_dialog_option_selected_background
                        : R.drawable
                        .hypernova_dialog_option_background);

        indicator.setImageResource(
                selected
                        ? R.drawable.hypernova_radio_selected
                        : R.drawable.hypernova_radio_unselected);

        row.setOnClickListener(listener);
    }

    private void configureDialogWindow(Dialog dialog) {

        Window window =
                dialog.getWindow();

        if (window == null) {
            return;
        }

        window.setWindowAnimations(
                R.style.HyperNovaDialogAnimation);

        window.setBackgroundDrawable(
                new ColorDrawable(
                        Color.TRANSPARENT));

        window.addFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        WindowManager.LayoutParams params =
                window.getAttributes();

        params.dimAmount = 0.68f;

        window.setAttributes(params);

        int parentWidth =
                requireActivity()
                        .getWindow()
                        .getDecorView()
                        .getWidth();

        if (parentWidth <= 0) {

            parentWidth =
                    getResources()
                            .getDisplayMetrics()
                            .widthPixels;
        }

        int horizontalMargin =
                dpToPx(42);

        int desiredWidth =
                Math.max(
                        dpToPx(300),
                        parentWidth
                                - (horizontalMargin * 2));

        window.setLayout(
                desiredWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dpToPx(int dp) {

        return Math.round(
                dp
                        * getResources()
                        .getDisplayMetrics()
                        .density);
    }

    // ============================================================
    // Theme write
    // ============================================================

    private void setDayNightMode(int mode) {

        Settings.Global.putInt(
                requireContext()
                        .getContentResolver(),
                FORCED_DAY_NIGHT_MODE,
                mode);

        refreshTheme();
    }
}
