/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.car.settings.hypernova;

import static android.car.settings.CarSettings.Global.FORCED_DAY_NIGHT_MODE;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.provider.Settings;

import com.hypernova.settings.R;
import com.android.car.settings.common.FragmentController;
import com.android.car.settings.common.MultiActionPreference;
import com.android.car.settings.display.ThemeTogglePreferenceController;

/** Adds the current real Auto/Light/Dark value to the official three-action theme controller. */
public final class HyperNovaThemeTogglePreferenceController
        extends ThemeTogglePreferenceController {

    private static final int MODE_AUTO = 0;
    private static final int MODE_LIGHT = 1;
    private static final int MODE_DARK = 2;

    public HyperNovaThemeTogglePreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions) {
        super(context, preferenceKey, fragmentController, uxRestrictions);
    }

    @Override
    protected void updateState(MultiActionPreference preference) {
        super.updateState(preference);
        int mode = Settings.Global.getInt(
                getContext().getContentResolver(), FORCED_DAY_NIGHT_MODE, MODE_AUTO);
        switch (mode) {
            case MODE_LIGHT:
                preference.setSummary(R.string.hypernova_theme_light);
                break;
            case MODE_DARK:
                preference.setSummary(R.string.hypernova_theme_dark);
                break;
            case MODE_AUTO:
            default:
                preference.setSummary(R.string.hypernova_theme_auto);
                break;
        }
    }
}
