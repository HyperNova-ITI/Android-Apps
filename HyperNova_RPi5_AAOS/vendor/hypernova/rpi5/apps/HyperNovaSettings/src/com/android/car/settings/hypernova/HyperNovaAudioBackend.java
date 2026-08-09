/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.car.settings.hypernova;

import android.car.media.CarAudioManager;
import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.settings.CarSettingsApplication;

/**
 * Resolves the most appropriate Android audio-volume backend.
 *
 * Priority:
 *
 * 1. Android Automotive CarAudioManager volume groups.
 * 2. Standard Android AudioManager STREAM_MUSIC.
 *
 * The standard Android fallback supports AAOS development boards that expose
 * a normal media stream but do not provide a usable car audio volume group.
 */
public final class HyperNovaAudioBackend {
    private static final String TAG = "HyperNovaAudio";

    public enum Mode {
        CAR_VOLUME_GROUP,
        MEDIA_STREAM,
        UNAVAILABLE
    }

    private final Context mContext;
    private final AudioManager mAudioManager;

    @Nullable
    private CarAudioManager mCarAudioManager;

    private Mode mMode = Mode.UNAVAILABLE;

    private int mZoneId = CarAudioManager.INVALID_AUDIO_ZONE;
    private int mGroupId = HyperNovaAudioUtils.INVALID_GROUP_ID;

    private int mMinimum;
    private int mMaximum;
    private int mCurrent;

    private HyperNovaAudioBackend(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mAudioManager = mContext.getSystemService(AudioManager.class);
        refresh();
    }

    @NonNull
    public static HyperNovaAudioBackend create(@NonNull Context context) {
        return new HyperNovaAudioBackend(context);
    }

    public void refresh() {
        reset();

        if (resolveCarAudio()) {
            return;
        }

        resolveMediaStream();
    }

    private void reset() {
        mMode = Mode.UNAVAILABLE;
        mCarAudioManager = null;

        mZoneId = CarAudioManager.INVALID_AUDIO_ZONE;
        mGroupId = HyperNovaAudioUtils.INVALID_GROUP_ID;

        mMinimum = 0;
        mMaximum = 0;
        mCurrent = 0;
    }

    private boolean resolveCarAudio() {
        try {
            if (!(mContext instanceof CarSettingsApplication)) {
                Log.w(TAG, "Application is not CarSettingsApplication");
                return false;
            }

            CarSettingsApplication application =
                    (CarSettingsApplication) mContext;

            CarAudioManager manager =
                    application.getCarAudioManager();

            int zoneId =
                    application.getMyAudioZoneId();

            if (manager == null
                    || zoneId == CarAudioManager.INVALID_AUDIO_ZONE) {
                Log.i(TAG, "No valid AAOS audio zone");
                return false;
            }

            int groupId =
                    HyperNovaAudioUtils.findMediaGroupId(mContext);

            if (groupId == HyperNovaAudioUtils.INVALID_GROUP_ID) {
                Log.i(TAG, "No media volume group");
                return false;
            }

            int minimum =
                    manager.getGroupMinVolume(zoneId, groupId);

            int maximum =
                    manager.getGroupMaxVolume(zoneId, groupId);

            int current =
                    manager.getGroupVolume(zoneId, groupId);

            if (maximum < minimum) {
                Log.w(TAG, "Invalid car volume range");
                return false;
            }

            mCarAudioManager = manager;

            mZoneId = zoneId;
            mGroupId = groupId;

            mMinimum = minimum;
            mMaximum = maximum;
            mCurrent = clamp(current, minimum, maximum);

            mMode = Mode.CAR_VOLUME_GROUP;

            Log.i(
                    TAG,
                    "Using CarAudioManager: zone="
                            + zoneId
                            + " group="
                            + groupId
                            + " range="
                            + minimum
                            + ".."
                            + maximum);

            return true;
        } catch (RuntimeException exception) {
            Log.w(
                    TAG,
                    "Car audio unavailable; trying STREAM_MUSIC",
                    exception);

            return false;
        }
    }

    private boolean resolveMediaStream() {
        if (mAudioManager == null) {
            Log.e(TAG, "AudioManager is unavailable");
            return false;
        }

        try {
            int minimum =
                    mAudioManager.getStreamMinVolume(
                            AudioManager.STREAM_MUSIC);

            int maximum =
                    mAudioManager.getStreamMaxVolume(
                            AudioManager.STREAM_MUSIC);

            int current =
                    mAudioManager.getStreamVolume(
                            AudioManager.STREAM_MUSIC);

            if (maximum <= 0 || maximum < minimum) {
                Log.w(TAG, "Invalid media stream range");
                return false;
            }

            mMinimum = minimum;
            mMaximum = maximum;
            mCurrent = clamp(current, minimum, maximum);

            mMode = Mode.MEDIA_STREAM;

            Log.i(
                    TAG,
                    "Using AudioManager STREAM_MUSIC: range="
                            + minimum
                            + ".."
                            + maximum);

            return true;
        } catch (RuntimeException exception) {
            Log.e(
                    TAG,
                    "Android media stream unavailable",
                    exception);

            return false;
        }
    }

    public boolean isAvailable() {
        return mMode != Mode.UNAVAILABLE;
    }

    @NonNull
    public Mode getMode() {
        return mMode;
    }

    public int getMinimum() {
        return mMinimum;
    }

    public int getMaximum() {
        return mMaximum;
    }

    public int getCurrent() {
        return mCurrent;
    }

    public int getPercentage() {
        if (!isAvailable()) {
            return 0;
        }

        if (mMaximum == mMinimum) {
            return 100;
        }

        return Math.round(
                (mCurrent - mMinimum)
                        * 100f
                        / (mMaximum - mMinimum));
    }

    public boolean setCurrent(int requestedValue) {
        if (!isAvailable() || mMaximum < mMinimum) {
            return false;
        }

        int value =
                clamp(
                        requestedValue,
                        mMinimum,
                        mMaximum);

        try {
            switch (mMode) {
                case CAR_VOLUME_GROUP:
                    if (mCarAudioManager == null) {
                        return false;
                    }

                    mCarAudioManager.setGroupVolume(
                            mZoneId,
                            mGroupId,
                            value,
                            0);

                    break;

                case MEDIA_STREAM:
                    if (mAudioManager == null) {
                        return false;
                    }

                    mAudioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            value,
                            0);

                    break;

                case UNAVAILABLE:
                default:
                    return false;
            }

            refresh();
            return true;
        } catch (RuntimeException exception) {
            Log.e(
                    TAG,
                    "Unable to set audio volume",
                    exception);

            refresh();
            return false;
        }
    }

    public boolean increase() {
        return setCurrent(mCurrent + 1);
    }

    public boolean decrease() {
        return setCurrent(mCurrent - 1);
    }

    private static int clamp(
            int value,
            int minimum,
            int maximum) {

        return Math.max(
                minimum,
                Math.min(value, maximum));
    }
}
