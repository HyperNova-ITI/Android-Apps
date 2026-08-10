/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.car.settings.hypernova;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;

import androidx.preference.SwitchPreference;

import com.hypernova.settings.R;
import com.android.car.settings.common.FragmentController;
import com.android.car.settings.datetime.AutoDatetimeTogglePreferenceController;

/** Keeps the official automatic-date/time behavior and adds the compact real state label. */
public final class HyperNovaAutoDateTimePreferenceController
        extends AutoDatetimeTogglePreferenceController {

    public HyperNovaAutoDateTimePreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions) {
        super(context, preferenceKey, fragmentController, uxRestrictions);
    }

    @Override
    protected void updateState(SwitchPreference preference) {
        super.updateState(preference);
        updateSummary(preference, preference.isChecked());
    }

    @Override
    protected boolean handlePreferenceChanged(SwitchPreference preference, Object newValue) {
        boolean accepted = super.handlePreferenceChanged(preference, newValue);
        if (accepted) {
            updateSummary(preference, (Boolean) newValue);
        }
        return accepted;
    }

    private void updateSummary(SwitchPreference preference, boolean enabled) {
        preference.setSummary(enabled
                ? R.string.hypernova_state_on : R.string.hypernova_state_off);
    }
}
