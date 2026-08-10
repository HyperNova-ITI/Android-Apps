/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.car.settings.hypernova;

import android.car.media.CarAudioManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.util.SparseArray;

import androidx.annotation.Nullable;

import com.android.car.settings.CarSettingsApplication;
import com.hypernova.settings.R;
import com.android.car.settings.sound.VolumeItemParser;
import com.android.car.settings.sound.VolumeItemParser.VolumeItem;

/** Shared read-only mapping between real CarAudio groups and AOSP's configured UI metadata. */
final class HyperNovaAudioUtils {
    static final int INVALID_GROUP_ID = -1;

    private HyperNovaAudioUtils() {
    }

    @Nullable
    static CarAudioManager getManager(Context context) {
        return ((CarSettingsApplication) context.getApplicationContext()).getCarAudioManager();
    }

    static int getZoneId(Context context) {
        return ((CarSettingsApplication) context.getApplicationContext()).getMyAudioZoneId();
    }

    static SparseArray<VolumeItem> loadVolumeItems(Context context) {
        return VolumeItemParser.loadAudioUsageItems(context, R.xml.car_volume_items);
    }

    static int findMediaGroupId(Context context) {
        CarAudioManager manager = getManager(context);
        int zoneId = getZoneId(context);
        if (manager == null || zoneId == CarAudioManager.INVALID_AUDIO_ZONE) {
            return INVALID_GROUP_ID;
        }
        try {
            int count = manager.getVolumeGroupCount(zoneId);
            for (int groupId = 0; groupId < count; groupId++) {
                for (int usage : manager.getUsagesForVolumeGroupId(zoneId, groupId)) {
                    if (usage == AudioAttributes.USAGE_MEDIA) {
                        return groupId;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Car service can be transiently unavailable during activity startup/teardown.
        }
        return INVALID_GROUP_ID;
    }

    @Nullable
    static VolumeItem resolveVolumeItem(SparseArray<VolumeItem> items, int[] usages) {
        VolumeItem result = null;
        int bestRank = Integer.MAX_VALUE;
        for (int usage : usages) {
            VolumeItem candidate = items.get(usage);
            if (candidate != null && candidate.getRank() < bestRank) {
                bestRank = candidate.getRank();
                result = candidate;
            }
        }
        return result;
    }

    @Nullable
    static VolumeItem resolveVolumeItem(Context context, int groupId,
            SparseArray<VolumeItem> items) {
        CarAudioManager manager = getManager(context);
        int zoneId = getZoneId(context);
        if (manager == null || zoneId == CarAudioManager.INVALID_AUDIO_ZONE
                || groupId == INVALID_GROUP_ID) {
            return null;
        }
        try {
            return resolveVolumeItem(items,
                    manager.getUsagesForVolumeGroupId(zoneId, groupId));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static int getPercentage(int minimum, int maximum, int current) {
        if (maximum <= minimum) {
            return 0;
        }
        return Math.round((current - minimum) * 100f / (maximum - minimum));
    }
}
