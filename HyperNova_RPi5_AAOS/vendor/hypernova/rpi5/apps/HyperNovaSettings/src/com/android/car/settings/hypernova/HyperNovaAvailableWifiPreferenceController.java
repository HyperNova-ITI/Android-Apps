/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.car.settings.hypernova;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;

import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;

import com.hypernova.settings.R;
import com.android.car.settings.common.FragmentController;
import com.android.car.settings.wifi.WifiEntryListPreferenceController;
import com.android.car.settings.wifi.WifiEntryPreference;
import com.android.wifitrackerlib.WifiEntry;

/**
 * Visual adapter around the official WifiEntryList controller.
 *
 * <p>The parent still owns WifiTrackerLib sorting, password dialogs, connect callbacks, saved
 * network forgetting, policy restrictions, and UXR filtering. This adapter only removes the
 * connected duplicate and assigns the compact HyperNova network-row presentation.</p>
 */
public final class HyperNovaAvailableWifiPreferenceController
        extends WifiEntryListPreferenceController {

    public HyperNovaAvailableWifiPreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions) {
        super(context, preferenceKey, fragmentController, uxRestrictions);
    }

    @Override
    protected void updateState(PreferenceGroup group) {
        super.updateState(group);
        boolean hasAvailableEntry = false;
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference preference = group.getPreference(index);
            if (!(preference instanceof WifiEntryPreference)) {
                continue;
            }
            WifiEntry entry = ((WifiEntryPreference) preference).getWifiEntry();
            boolean available = entry.getConnectedState() == WifiEntry.CONNECTED_STATE_DISCONNECTED;
            preference.setVisible(available);
            if (available) {
                preference.setLayoutResource(R.layout.hypernova_network_row);
                hasAvailableEntry = true;
            } else {
                // Avoid duplicating the connected card's WifiEntry key in the same screen.
                preference.setKey("hypernova_hidden_connected_" + index);
            }
        }
        group.setVisible(hasAvailableEntry);
    }
}
