/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.car.settings.hypernova;

import static android.car.media.CarAudioManager.AUDIO_FEATURE_VOLUME_GROUP_EVENTS;

import android.car.media.CarAudioManager;
import android.car.media.CarVolumeGroupEvent;
import android.car.media.CarVolumeGroupEventCallback;
import android.content.Context;

import java.util.List;

/** Lifecycle-neutral bridge from either supported CarAudio callback API to a UI refresh. */
final class HyperNovaAudioCallbackDelegate {
    private final Runnable mRefresh;
    private boolean mRegistered;
    private boolean mUsingGroupEvents;
    private CarAudioManager mManager;

    private final CarAudioManager.CarVolumeCallback mLegacyCallback =
            new CarAudioManager.CarVolumeCallback() {
                @Override
                public void onGroupVolumeChanged(int zoneId, int groupId, int flags) {
                    mRefresh.run();
                }

                @Override
                public void onMasterMuteChanged(int zoneId, int flags) {
                    mRefresh.run();
                }

                @Override
                public void onGroupMuteChanged(int zoneId, int groupId, int flags) {
                    mRefresh.run();
                }
            };

    private final CarVolumeGroupEventCallback mGroupEventCallback =
            new CarVolumeGroupEventCallback() {
                @Override
                public void onVolumeGroupEvent(List<CarVolumeGroupEvent> events) {
                    mRefresh.run();
                }
            };

    HyperNovaAudioCallbackDelegate(Runnable refresh) {
        mRefresh = refresh;
    }

    void start(Context context) {
        if (mRegistered) {
            return;
        }
        mManager = HyperNovaAudioUtils.getManager(context);
        if (mManager == null) {
            return;
        }
        try {
            mUsingGroupEvents = mManager.isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_EVENTS);
            if (mUsingGroupEvents) {
                mManager.registerCarVolumeGroupEventCallback(
                        context.getMainExecutor(), mGroupEventCallback);
            } else {
                mManager.registerCarVolumeCallback(mLegacyCallback);
            }
            mRegistered = true;
        } catch (RuntimeException exception) {
            mManager = null;
            mRegistered = false;
        }
    }

    void stop() {
        if (!mRegistered || mManager == null) {
            return;
        }
        try {
            if (mUsingGroupEvents) {
                mManager.unregisterCarVolumeGroupEventCallback(mGroupEventCallback);
            } else {
                mManager.unregisterCarVolumeCallback(mLegacyCallback);
            }
        } catch (RuntimeException ignored) {
            // Car service teardown races are expected while an activity is stopping.
        }
        mRegistered = false;
        mManager = null;
    }
}
