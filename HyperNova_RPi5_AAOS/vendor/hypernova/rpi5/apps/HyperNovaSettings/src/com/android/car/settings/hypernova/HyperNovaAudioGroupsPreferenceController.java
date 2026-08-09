/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.car.settings.hypernova;

import android.car.drivingstate.CarUxRestrictions;
import android.car.media.CarAudioManager;
import android.content.Context;
import android.util.SparseArray;

import androidx.preference.PreferenceGroup;

import com.hypernova.settings.R;
import com.android.car.settings.common.FragmentController;
import com.android.car.settings.common.PreferenceController;
import com.android.car.settings.sound.VolumeItemParser.VolumeItem;
import com.android.car.ui.preference.CarUiPreference;

/** Generates one compact navigation row for each real volume group in the active audio zone. */
public final class HyperNovaAudioGroupsPreferenceController
        extends PreferenceController<PreferenceGroup> {

    private final SparseArray<VolumeItem> mVolumeItems;

    public HyperNovaAudioGroupsPreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions) {
        super(context, preferenceKey, fragmentController, uxRestrictions);
        mVolumeItems = HyperNovaAudioUtils.loadVolumeItems(context);
    }

    @Override
    protected Class<PreferenceGroup> getPreferenceType() {
        return PreferenceGroup.class;
    }

    @Override
    protected void updateState(PreferenceGroup group) {
        group.removeAll();
        CarAudioManager manager = HyperNovaAudioUtils.getManager(getContext());
        int zoneId = HyperNovaAudioUtils.getZoneId(getContext());
        if (manager == null || zoneId == CarAudioManager.INVALID_AUDIO_ZONE) {
            group.setVisible(false);
            return;
        }

        try {
            int count = manager.getVolumeGroupCount(zoneId);
            group.setVisible(count > 0);
            for (int groupId = 0; groupId < count; groupId++) {
                group.addPreference(createGroupPreference(manager, zoneId, groupId));
            }
        } catch (RuntimeException exception) {
            group.setVisible(false);
        }
    }

    private CarUiPreference createGroupPreference(
            CarAudioManager manager, int zoneId, int groupId) {
        CarUiPreference preference = new CarUiPreference(getContext());
        preference.setKey(getPreferenceKey() + "_" + groupId);
        preference.setLayoutResource(R.layout.hypernova_audio_group_row);
        preference.setShowChevron(true);

        VolumeItem item = HyperNovaAudioUtils.resolveVolumeItem(
                getContext(), groupId, mVolumeItems);
        if (item == null) {
            preference.setTitle(R.string.hypernova_audio_group_volume);
            preference.setIcon(R.drawable.hypernova_ic_volume);
        } else {
            preference.setTitle(item.getTitle());
            preference.setIcon(item.getIcon());
        }

        int minimum = manager.getGroupMinVolume(zoneId, groupId);
        int maximum = manager.getGroupMaxVolume(zoneId, groupId);
        int current = manager.getGroupVolume(zoneId, groupId);
        preference.setSummary(getContext().getString(R.string.hypernova_percent,
                HyperNovaAudioUtils.getPercentage(minimum, maximum, current)));
        preference.setOnPreferenceClickListener(clicked -> {
            HyperNovaSettingsActivities.startAudioGroupActivity(getContext(), groupId);
            return true;
        });
        return preference;
    }
}
