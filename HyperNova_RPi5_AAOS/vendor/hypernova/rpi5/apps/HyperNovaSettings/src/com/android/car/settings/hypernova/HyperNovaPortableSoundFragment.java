/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.car.settings.hypernova;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hypernova.settings.R;

/**
 * HyperNova Sound surface that works with both:
 *
 * - AAOS CarAudioManager volume groups.
 * - Standard Android STREAM_MUSIC volume.
 */
public final class HyperNovaPortableSoundFragment extends Fragment {
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private TextView mBackendTitle;
    private TextView mBackendSummary;
    private TextView mVolumeValue;
    private TextView mResultMessage;
    private SeekBar mVolumeSeekBar;
    private ImageButton mDecreaseButton;
    private ImageButton mIncreaseButton;
    private Button mTestSoundButton;

    private HyperNovaAudioBackend mAudioBackend;

    @Nullable
    private ToneGenerator mToneGenerator;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(
                R.layout.hypernova_portable_sound_fragment,
                container,
                false);
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        HyperNovaSettingsActivities.configureHeader(
                this,
                R.string.hypernova_sound,
                true);

        mBackendTitle = view.findViewById(
                R.id.hypernova_portable_audio_backend_title);
        mBackendSummary = view.findViewById(
                R.id.hypernova_portable_audio_backend_summary);
        mVolumeValue = view.findViewById(
                R.id.hypernova_portable_audio_value);
        mResultMessage = view.findViewById(
                R.id.hypernova_portable_audio_result);
        mVolumeSeekBar = view.findViewById(
                R.id.hypernova_portable_audio_seekbar);
        mDecreaseButton = view.findViewById(
                R.id.hypernova_portable_audio_decrease);
        mIncreaseButton = view.findViewById(
                R.id.hypernova_portable_audio_increase);
        mTestSoundButton = view.findViewById(
                R.id.hypernova_portable_audio_test);

        mVolumeSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser) {
                        if (!fromUser || mAudioBackend == null) {
                            return;
                        }

                        int requested =
                                mAudioBackend.getMinimum() + progress;

                        boolean applied =
                                mAudioBackend.setCurrent(requested);

                        showApplyResult(applied);
                        renderCurrentValue();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        refreshAudioState();
                    }
                });

        mDecreaseButton.setOnClickListener(button -> {
            if (mAudioBackend != null) {
                showApplyResult(mAudioBackend.decrease());
                refreshAudioState();
            }
        });

        mIncreaseButton.setOnClickListener(button -> {
            if (mAudioBackend != null) {
                showApplyResult(mAudioBackend.increase());
                refreshAudioState();
            }
        });

        mTestSoundButton.setOnClickListener(
                button -> playTestSound());
    }

    @Override
    public void onStart() {
        super.onStart();
        refreshAudioState();
    }

    @Override
    public void onStop() {
        releaseToneGenerator();
        super.onStop();
    }

    private void refreshAudioState() {
        mAudioBackend =
                HyperNovaAudioBackend.create(requireContext());

        boolean available = mAudioBackend.isAvailable();

        mVolumeSeekBar.setEnabled(available);
        mDecreaseButton.setEnabled(available);
        mIncreaseButton.setEnabled(available);
        mTestSoundButton.setEnabled(available);

        if (!available) {
            mBackendTitle.setText(
                    R.string.hypernova_audio_backend_unavailable);
            mBackendSummary.setText(
                    R.string.hypernova_audio_backend_unavailable_summary);
            mVolumeValue.setText(
                    R.string.hypernova_state_unavailable);
            mVolumeSeekBar.setMax(1);
            mVolumeSeekBar.setProgress(0);
            return;
        }

        if (mAudioBackend.getMode()
                == HyperNovaAudioBackend.Mode.CAR_VOLUME_GROUP) {
            mBackendTitle.setText(
                    R.string.hypernova_audio_backend_vehicle);
            mBackendSummary.setText(
                    R.string.hypernova_audio_backend_vehicle_summary);
        } else {
            mBackendTitle.setText(
                    R.string.hypernova_audio_backend_media);
            mBackendSummary.setText(
                    R.string.hypernova_audio_backend_media_summary);
        }

        int range = Math.max(
                1,
                mAudioBackend.getMaximum()
                        - mAudioBackend.getMinimum());

        mVolumeSeekBar.setMax(range);
        mVolumeSeekBar.setProgress(
                mAudioBackend.getCurrent()
                        - mAudioBackend.getMinimum());

        renderCurrentValue();
    }

    private void renderCurrentValue() {
        if (mAudioBackend == null
                || !mAudioBackend.isAvailable()) {
            mVolumeValue.setText(
                    R.string.hypernova_state_unavailable);
            return;
        }

        mVolumeValue.setText(getString(
                R.string.hypernova_percent,
                mAudioBackend.getPercentage()));
    }

    private void showApplyResult(boolean applied) {
        mResultMessage.setVisibility(View.VISIBLE);
        mResultMessage.setText(applied
                ? R.string.hypernova_audio_volume_applied
                : R.string.hypernova_audio_volume_failed);

        mMainHandler.removeCallbacks(
                mHideResultMessage);

        mMainHandler.postDelayed(
                mHideResultMessage,
                1800);
    }

    private final Runnable mHideResultMessage = () -> {
        if (mResultMessage != null) {
            mResultMessage.setVisibility(View.INVISIBLE);
        }
    };

    private void playTestSound() {
        releaseToneGenerator();

        try {
            mToneGenerator = new ToneGenerator(
                    AudioManager.STREAM_MUSIC,
                    90);

            boolean started = mToneGenerator.startTone(
                    ToneGenerator.TONE_PROP_BEEP,
                    600);

            mResultMessage.setVisibility(View.VISIBLE);
            mResultMessage.setText(started
                    ? R.string.hypernova_audio_test_playing
                    : R.string.hypernova_audio_test_failed);

            mMainHandler.postDelayed(
                    this::releaseToneGenerator,
                    900);
        } catch (RuntimeException exception) {
            releaseToneGenerator();
            mResultMessage.setVisibility(View.VISIBLE);
            mResultMessage.setText(
                    R.string.hypernova_audio_test_failed);
        }
    }

    private void releaseToneGenerator() {
        if (mToneGenerator == null) {
            return;
        }

        try {
            mToneGenerator.stopTone();
            mToneGenerator.release();
        } catch (RuntimeException ignored) {
            // The audio service may disappear while the fragment stops.
        }

        mToneGenerator = null;
    }
}
