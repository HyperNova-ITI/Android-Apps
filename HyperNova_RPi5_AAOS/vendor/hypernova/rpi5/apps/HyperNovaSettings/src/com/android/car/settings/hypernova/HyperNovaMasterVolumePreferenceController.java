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

/**
 * Presents the real AAOS media-usage volume group as the optional master control.
 *
 * <p>If the active audio configuration has no group containing {@code USAGE_MEDIA}, the entire
 * section is omitted rather than inventing a master-volume concept.</p>
 */
public final class HyperNovaMasterVolumePreferenceController
        extends PreferenceController<PreferenceGroup> {

    private final SparseArray<VolumeItem> mVolumeItems;
    private HyperNovaSliderPreference mSlider;
    private int mBoundGroupId = HyperNovaAudioUtils.INVALID_GROUP_ID;

    public HyperNovaMasterVolumePreferenceController(Context context, String preferenceKey,
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
        int groupId = HyperNovaAudioUtils.findMediaGroupId(getContext());
        CarAudioManager manager = HyperNovaAudioUtils.getManager(getContext());
        int zoneId = HyperNovaAudioUtils.getZoneId(getContext());
        if (manager == null || zoneId == CarAudioManager.INVALID_AUDIO_ZONE
                || groupId == HyperNovaAudioUtils.INVALID_GROUP_ID) {
            group.removeAll();
            group.setVisible(false);
            mSlider = null;
            mBoundGroupId = HyperNovaAudioUtils.INVALID_GROUP_ID;
            return;
        }

        group.setVisible(true);
        if (mSlider == null || mBoundGroupId != groupId) {
            group.removeAll();
            mBoundGroupId = groupId;
            mSlider = new HyperNovaSliderPreference(getContext());
            mSlider.setKey(getPreferenceKey() + "_slider");
            mSlider.setTitle(R.string.hypernova_master_volume);
            mSlider.setContinuousUpdate(true);
            mSlider.setShowSeekBarValue(false);
            mSlider.setOnPreferenceChangeListener((preference, newValue) ->
                    setGroupVolume((Integer) newValue));
            group.addPreference(mSlider);
        }

        try {
            int minimum = manager.getGroupMinVolume(zoneId, groupId);
            int maximum = manager.getGroupMaxVolume(zoneId, groupId);
            int current = manager.getGroupVolume(zoneId, groupId);
            mSlider.setMin(minimum);
            mSlider.setMax(maximum);
            mSlider.setValue(current);
            mSlider.setSummary(getContext().getString(R.string.hypernova_percent,
                    HyperNovaAudioUtils.getPercentage(minimum, maximum, current)));
            VolumeItem item = HyperNovaAudioUtils.resolveVolumeItem(
                    getContext(), groupId, mVolumeItems);
            mSlider.setIcon(item == null ? R.drawable.hypernova_ic_volume : item.getIcon());
        } catch (RuntimeException exception) {
            group.setVisible(false);
        }
    }

    private boolean setGroupVolume(int value) {
        CarAudioManager manager = HyperNovaAudioUtils.getManager(getContext());
        int zoneId = HyperNovaAudioUtils.getZoneId(getContext());
        if (manager == null || zoneId == CarAudioManager.INVALID_AUDIO_ZONE
                || mBoundGroupId == HyperNovaAudioUtils.INVALID_GROUP_ID) {
            return false;
        }
        try {
            manager.setGroupVolume(zoneId, mBoundGroupId, value, /* flags= */ 0);
            int minimum = manager.getGroupMinVolume(zoneId, mBoundGroupId);
            int maximum = manager.getGroupMaxVolume(zoneId, mBoundGroupId);
            mSlider.setSummary(getContext().getString(R.string.hypernova_percent,
                    HyperNovaAudioUtils.getPercentage(minimum, maximum, value)));
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
