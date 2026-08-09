/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.car.settings.hypernova;

import android.car.media.CarAudioManager;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.hypernova.settings.R;
import com.android.car.settings.common.BaseFragment;
import com.android.car.settings.sound.VolumeItemParser.VolumeItem;

/**
 * Full-width HyperNova detail screen for one real AAOS volume group.
 */
public final class HyperNovaAudioGroupDetailFragment extends BaseFragment {

    private static final String ARG_GROUP_ID =
            "hypernova_audio_group_id";

    private final HyperNovaAudioCallbackDelegate mAudioCallbacks =
            new HyperNovaAudioCallbackDelegate(this::refreshAudioState);

    private int mGroupId =
            HyperNovaAudioUtils.INVALID_GROUP_ID;

    private CarAudioManager mAudioManager;

    private int mZoneId =
            CarAudioManager.INVALID_AUDIO_ZONE;

    private int mMinimum;
    private int mMaximum;

    private boolean mRefreshing;

    private ImageView mIcon;
    private TextView mTitle;
    private TextView mValue;
    private SeekBar mSeekBar;

    static HyperNovaAudioGroupDetailFragment newInstance(
            int groupId) {

        HyperNovaAudioGroupDetailFragment fragment =
                new HyperNovaAudioGroupDetailFragment();

        Bundle arguments =
                new Bundle();

        arguments.putInt(
                ARG_GROUP_ID,
                groupId);

        fragment.setArguments(
                arguments);

        return fragment;
    }

    @Override
    protected int getLayoutId() {
        return R.layout.hypernova_audio_group_detail_fragment;
    }

    @Override
    @StringRes
    protected int getTitleId() {
        return R.string.hypernova_sound;
    }

    @Override
    public void onCreate(
            Bundle savedInstanceState) {

        super.onCreate(
                savedInstanceState);

        Bundle arguments =
                getArguments();

        if (arguments != null) {

            mGroupId =
                    arguments.getInt(
                            ARG_GROUP_ID,
                            HyperNovaAudioUtils.INVALID_GROUP_ID);
        }
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            Bundle savedInstanceState) {

        super.onViewCreated(
                view,
                savedInstanceState);

        mIcon =
                view.findViewById(
                        R.id.hypernova_audio_group_detail_icon);

        mTitle =
                view.findViewById(
                        R.id.hypernova_audio_group_detail_title);

        mValue =
                view.findViewById(
                        R.id.hypernova_audio_group_detail_value);

        mSeekBar =
                view.findViewById(
                        R.id.hypernova_audio_group_detail_seekbar);

        View decrease =
                view.findViewById(
                        R.id.hypernova_audio_group_detail_decrease);

        View increase =
                view.findViewById(
                        R.id.hypernova_audio_group_detail_increase);

        bindGroupMetadata();

        mSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser) {

                        int value =
                                mMinimum
                                        + progress;

                        updatePercentage(
                                value);

                        if (!fromUser
                                || mRefreshing) {

                            return;
                        }

                        setVolume(
                                value);
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar) {
                    }
                });

        decrease.setOnClickListener(
                clicked ->
                        adjustVolume(-1));

        increase.setOnClickListener(
                clicked ->
                        adjustVolume(1));

        refreshAudioState();
    }

    @Override
    public void onStart() {
        super.onStart();

        mAudioCallbacks.start(
                requireContext());

        refreshAudioState();
    }

    @Override
    public void onStop() {

        mAudioCallbacks.stop();

        super.onStop();
    }

    private void bindGroupMetadata() {

        SparseArray<VolumeItem> items =
                HyperNovaAudioUtils.loadVolumeItems(
                        requireContext());

        VolumeItem item =
                HyperNovaAudioUtils.resolveVolumeItem(
                        requireContext(),
                        mGroupId,
                        items);

        CharSequence screenTitle;

        if (item != null) {

            mTitle.setText(
                    item.getTitle());

            mIcon.setImageResource(
                    item.getIcon());

            screenTitle =
                    getString(
                            item.getTitle());

        } else {

            String fallback =
                    getString(
                            R.string.hypernova_audio_group_volume);

            mTitle.setText(
                    fallback);

            mIcon.setImageResource(
                    R.drawable.hypernova_ic_volume);

            screenTitle =
                    fallback;
        }

        HyperNovaSettingsActivities.configureHeader(
                this,
                screenTitle,
                /* showBack= */ true);
    }

    private void refreshAudioState() {

        if (!isAdded()
                || mSeekBar == null) {
            return;
        }

        mAudioManager =
                HyperNovaAudioUtils.getManager(
                        requireContext());

        mZoneId =
                HyperNovaAudioUtils.getZoneId(
                        requireContext());

        if (mAudioManager == null
                || mZoneId
                == CarAudioManager.INVALID_AUDIO_ZONE
                || mGroupId
                == HyperNovaAudioUtils.INVALID_GROUP_ID) {

            setControlsEnabled(
                    false);

            return;
        }

        try {

            mMinimum =
                    mAudioManager.getGroupMinVolume(
                            mZoneId,
                            mGroupId);

            mMaximum =
                    mAudioManager.getGroupMaxVolume(
                            mZoneId,
                            mGroupId);

            int current =
                    mAudioManager.getGroupVolume(
                            mZoneId,
                            mGroupId);

            mRefreshing = true;

            mSeekBar.setMax(
                    Math.max(
                            0,
                            mMaximum
                                    - mMinimum));

            mSeekBar.setProgress(
                    Math.max(
                            0,
                            current
                                    - mMinimum));

            updatePercentage(
                    current);

            setControlsEnabled(
                    true);

            mRefreshing = false;

        } catch (RuntimeException exception) {

            mRefreshing = false;

            setControlsEnabled(
                    false);
        }
    }

    private void updatePercentage(
            int value) {

        int percentage =
                HyperNovaAudioUtils.getPercentage(
                        mMinimum,
                        mMaximum,
                        value);

        mValue.setText(
                getString(
                        R.string.hypernova_percent,
                        percentage));
    }

    private void adjustVolume(
            int delta) {

        if (mAudioManager == null) {
            return;
        }

        try {

            int current =
                    mAudioManager.getGroupVolume(
                            mZoneId,
                            mGroupId);

            int next =
                    Math.max(
                            mMinimum,
                            Math.min(
                                    mMaximum,
                                    current + delta));

            if (next == current) {
                return;
            }

            setVolume(
                    next);

            mRefreshing = true;

            mSeekBar.setProgress(
                    next
                            - mMinimum);

            updatePercentage(
                    next);

            mRefreshing = false;

        } catch (RuntimeException ignored) {
        }
    }

    private void setVolume(
            int value) {

        if (mAudioManager == null) {
            return;
        }

        try {

            int safeValue =
                    Math.max(
                            mMinimum,
                            Math.min(
                                    mMaximum,
                                    value));

            mAudioManager.setGroupVolume(
                    mZoneId,
                    mGroupId,
                    safeValue,
                    /* flags= */ 0);

            updatePercentage(
                    safeValue);

        } catch (RuntimeException ignored) {
        }
    }

    private void setControlsEnabled(
            boolean enabled) {

        mSeekBar.setEnabled(
                enabled);

        if (!enabled) {

            mValue.setText(
                    R.string.hypernova_state_unavailable);
        }
    }
}
