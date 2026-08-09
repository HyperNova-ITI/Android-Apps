/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.car.settings.hypernova;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;

import androidx.preference.PreferenceGroup;

import com.hypernova.settings.R;
import com.android.car.settings.common.FragmentController;
import com.android.car.settings.wifi.WifiBasePreferenceController;
import com.android.car.settings.wifi.details.WifiDetailsFragment;
import com.android.car.ui.preference.CarUiPreference;
import com.android.wifitrackerlib.WifiEntry;

import java.util.List;

/** Presents the real primary WifiTrackerLib connection in its own highlighted card. */
public final class HyperNovaConnectedWifiPreferenceController
        extends WifiBasePreferenceController<PreferenceGroup> {

    public HyperNovaConnectedWifiPreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions) {
        super(context, preferenceKey, fragmentController, uxRestrictions);
    }

    @Override
    protected Class<PreferenceGroup> getPreferenceType() {
        return PreferenceGroup.class;
    }

    @Override
    protected void updateState(PreferenceGroup group) {
        group.removeAll();
        List<WifiEntry> connectedEntries = getCarWifiManager().getConnectedWifiEntries();
        if (connectedEntries.isEmpty()) {
            group.setVisible(false);
            return;
        }

        WifiEntry entry = connectedEntries.get(0);
        CarUiPreference preference = new CarUiPreference(getContext());
        preference.setKey(entry.getKey());
        preference.setLayoutResource(R.layout.hypernova_connected_network_row);
        preference.setWidgetLayoutResource(R.layout.hypernova_info_widget);
        preference.setIcon(R.drawable.hypernova_ic_wifi);
        preference.setTitle(entry.getTitle());
        preference.setSummary(R.string.hypernova_connected);
        preference.setOnPreferenceClickListener(clicked -> {
            if (entry.canSignIn()) {
                entry.signIn(/* callback= */ null);
            } else {
                getFragmentController().launchFragment(WifiDetailsFragment.getInstance(entry));
            }
            return true;
        });
        group.addPreference(preference);
        group.setVisible(true);
    }

    @Override
    public void onWifiEntriesChanged() {
        refreshUi();
    }

    @Override
    public void onWifiStateChanged(int state) {
        refreshUi();
    }
}
