/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.car.settings.hypernova;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;

import com.hypernova.settings.R;
import com.android.car.settings.common.ColoredSwitchPreference;
import com.android.car.settings.common.FragmentController;
import com.android.car.settings.wifi.WifiStateSwitchPreferenceController;

/** Adds the compact real On/Off state label to the official Wi-Fi switch controller. */
public final class HyperNovaWifiStatePreferenceController
        extends WifiStateSwitchPreferenceController {

    public HyperNovaWifiStatePreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions) {
        super(context, preferenceKey, fragmentController, uxRestrictions);
    }

    @Override
    protected void updateState(ColoredSwitchPreference preference) {
        super.updateState(preference);
        preference.setSummary(preference.isChecked()
                ? R.string.hypernova_state_on : R.string.hypernova_state_off);
    }
}
