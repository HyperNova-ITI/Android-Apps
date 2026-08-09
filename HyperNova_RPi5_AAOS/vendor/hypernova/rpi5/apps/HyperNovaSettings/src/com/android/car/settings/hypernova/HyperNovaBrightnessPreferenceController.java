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

import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;

import com.hypernova.settings.R;
import com.android.car.settings.common.FragmentController;
import com.android.car.settings.common.SeekBarPreference;
import com.android.car.settings.display.BrightnessLevelPreferenceController;

/**
 * View-only decoration of the official brightness controller.
 *
 * <p>The parent owns observation, restrictions, gamma/linear conversion and persistence.</p>
 */
public final class HyperNovaBrightnessPreferenceController
        extends BrightnessLevelPreferenceController {

    public HyperNovaBrightnessPreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions) {
        super(context, preferenceKey, fragmentController, uxRestrictions);
    }

    @Override
    protected void updateState(SeekBarPreference preference) {
        super.updateState(preference);
        int percentage = Math.round(preference.getValue() * 100f / GAMMA_SPACE_MAX);
        preference.setSummary(getContext().getString(R.string.hypernova_percent, percentage));
    }
}
