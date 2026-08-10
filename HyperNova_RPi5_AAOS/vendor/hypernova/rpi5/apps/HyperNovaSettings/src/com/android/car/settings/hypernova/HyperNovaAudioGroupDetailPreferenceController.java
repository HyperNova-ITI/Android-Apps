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

import com.hypernova.settings.R;
import com.android.car.settings.common.FragmentController;
import com.android.car.settings.common.PreferenceController;
import com.android.car.settings.sound.VolumeItemParser.VolumeItem;

/** Owns a dedicated compact slider for one real AAOS audio group. */
public final class HyperNovaAudioGroupDetailPreferenceController
        extends PreferenceController<HyperNovaSliderPreference> {

    private final SparseArray<VolumeItem> mVolumeItems;
    private int mGroupId = HyperNovaAudioUtils.INVALID_GROUP_ID;

    public HyperNovaAudioGroupDetailPreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions) {
        super(context, preferenceKey, fragmentController, uxRestrictions);
        mVolumeItems = HyperNovaAudioUtils.loadVolumeItems(context);
    }

    HyperNovaAudioGroupDetailPreferenceController init(int groupId) {
        mGroupId = groupId;
        return this;
    }

    @Override
    protected void checkInitialized() {
        if (mGroupId == HyperNovaAudioUtils.INVALID_GROUP_ID) {
            throw new IllegalStateException("A real CarAudio volume group id is required");
        }
    }

    @Override
    protected Class<HyperNovaSliderPreference> getPreferenceType() {
        return HyperNovaSliderPreference.class;
    }

    @Override
    protected void onCreateInternal() {
        getPreference().setContinuousUpdate(true);
        getPreference().setShowSeekBarValue(false);
    }

    @Override
    protected void updateState(HyperNovaSliderPreference preference) {
        CarAudioManager manager = HyperNovaAudioUtils.getManager(getContext());
        int zoneId = HyperNovaAudioUtils.getZoneId(getContext());
        if (manager == null || zoneId == CarAudioManager.INVALID_AUDIO_ZONE) {
            preference.setEnabled(false);
            return;
        }
        try {
            int minimum = manager.getGroupMinVolume(zoneId, mGroupId);
            int maximum = manager.getGroupMaxVolume(zoneId, mGroupId);
            int current = manager.getGroupVolume(zoneId, mGroupId);
            preference.setMin(minimum);
            preference.setMax(maximum);
            preference.setValue(current);
            preference.setSummary(getContext().getString(R.string.hypernova_percent,
                    HyperNovaAudioUtils.getPercentage(minimum, maximum, current)));
            VolumeItem item = HyperNovaAudioUtils.resolveVolumeItem(
                    getContext(), mGroupId, mVolumeItems);
            if (item != null) {
                preference.setTitle(item.getTitle());
                preference.setIcon(item.getIcon());
            }
        } catch (RuntimeException exception) {
            preference.setEnabled(false);
        }
    }

    @Override
    protected boolean handlePreferenceChanged(
            HyperNovaSliderPreference preference, Object newValue) {
        CarAudioManager manager = HyperNovaAudioUtils.getManager(getContext());
        int zoneId = HyperNovaAudioUtils.getZoneId(getContext());
        if (manager == null || zoneId == CarAudioManager.INVALID_AUDIO_ZONE) {
            return false;
        }
        try {
            int value = (Integer) newValue;
            manager.setGroupVolume(zoneId, mGroupId, value, /* flags= */ 0);
            int minimum = manager.getGroupMinVolume(zoneId, mGroupId);
            int maximum = manager.getGroupMaxVolume(zoneId, mGroupId);
            preference.setSummary(getContext().getString(R.string.hypernova_percent,
                    HyperNovaAudioUtils.getPercentage(minimum, maximum, value)));
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
