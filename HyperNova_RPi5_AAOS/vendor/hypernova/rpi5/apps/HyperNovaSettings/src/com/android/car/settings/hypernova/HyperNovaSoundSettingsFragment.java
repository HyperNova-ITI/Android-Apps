/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.android.car.settings.hypernova;

import static android.car.media.CarAudioManager.AUDIO_FEATURE_DYNAMIC_ROUTING;

import android.car.media.CarAudioManager;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.hypernova.settings.R;
import com.android.car.settings.common.BaseFragment;
import com.android.car.settings.sound.VolumeItemParser.VolumeItem;
import com.android.car.settings.sound.VolumeSettingsRingtoneManager;

/**
 * Full-width HyperNova Sound screen.
 *
 * Every real AAOS volume group can be adjusted directly from this screen.
 * Volume changes continue through CarAudioManager.
 */
public final class HyperNovaSoundSettingsFragment extends BaseFragment {

    private final HyperNovaAudioCallbackDelegate mAudioCallbacks =
            new HyperNovaAudioCallbackDelegate(this::refreshAudioState);

    private final SparseArray<GroupControls> mGroupControls =
            new SparseArray<>();

    private View mMasterSectionHeader;
    private View mMasterCard;

    private SeekBar mMasterSeekBar;
    private TextView mMasterValue;

    private View mGroupsSectionHeader;
    private View mGroupsCard;
    private LinearLayout mGroupsContainer;

    private CarAudioManager mAudioManager;

    private SparseArray<VolumeItem> mVolumeItems;

    private VolumeSettingsRingtoneManager mRingtoneManager;

    private int mZoneId =
            CarAudioManager.INVALID_AUDIO_ZONE;

    private int mMediaGroupId =
            HyperNovaAudioUtils.INVALID_GROUP_ID;

    private int mMasterUsage =
            AudioAttributes.USAGE_MEDIA;

    private int mMasterMinimum;
    private int mMasterMaximum;

    private boolean mRefreshing;

    /**
     * Runtime controls belonging to one real AAOS volume group.
     */
    private static final class GroupControls {

        final int groupId;
        final int usage;

        final SeekBar seekBar;
        final TextView value;

        int minimum;
        int maximum;

        GroupControls(
                int groupId,
                int usage,
                SeekBar seekBar,
                TextView value) {

            this.groupId = groupId;
            this.usage = usage;
            this.seekBar = seekBar;
            this.value = value;
        }
    }

    @Override
    protected int getLayoutId() {
        return R.layout.hypernova_sound_fragment;
    }

    @Override
    @StringRes
    protected int getTitleId() {
        return R.string.hypernova_sound;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mVolumeItems =
                HyperNovaAudioUtils.loadVolumeItems(
                        requireContext());

        /*
         * This is the same feedback helper used by AOSP CarSettings.
         */
        mRingtoneManager =
                new VolumeSettingsRingtoneManager(
                        requireContext());
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            Bundle savedInstanceState) {

        super.onViewCreated(
                view,
                savedInstanceState);

        HyperNovaSettingsActivities.configureHeader(
                this,
                R.string.hypernova_sound,
                /* showBack= */ true);

        bindMasterVolume(view);

        bindAudioGroups(view);

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

        if (mRingtoneManager != null) {
            mRingtoneManager.stopCurrentRingtone();
        }

        super.onStop();
    }

    @Override
    public void onDestroyView() {

        mGroupControls.clear();

        super.onDestroyView();
    }

    // ============================================================
    // MASTER VOLUME
    // ============================================================

    private void bindMasterVolume(View root) {

        mMasterSectionHeader =
                root.findViewById(
                        R.id.hypernova_master_section_header);

        mMasterCard =
                root.findViewById(
                        R.id.hypernova_master_volume_card);

        mMasterSeekBar =
                root.findViewById(
                        R.id.hypernova_master_volume_seekbar);

        mMasterValue =
                root.findViewById(
                        R.id.hypernova_master_volume_value);

        View decrease =
                root.findViewById(
                        R.id.hypernova_master_volume_decrease);

        View increase =
                root.findViewById(
                        R.id.hypernova_master_volume_increase);

        mMasterSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser) {

                        int value =
                                mMasterMinimum
                                        + progress;

                        updateMasterPercentage(
                                value);

                        if (!fromUser
                                || mRefreshing) {

                            return;
                        }

                        setGroupVolume(
                                mMediaGroupId,
                                mMasterUsage,
                                value,
                                /* playFeedback= */ true);
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
                        adjustMasterVolume(-1));

        increase.setOnClickListener(
                clicked ->
                        adjustMasterVolume(1));
    }

    private void refreshMasterVolume() {

        mMediaGroupId =
                HyperNovaAudioUtils.findMediaGroupId(
                        requireContext());

        if (mAudioManager == null
                || mZoneId
                == CarAudioManager.INVALID_AUDIO_ZONE
                || mMediaGroupId
                == HyperNovaAudioUtils.INVALID_GROUP_ID) {

            mMasterSectionHeader.setVisibility(
                    View.GONE);

            mMasterCard.setVisibility(
                    View.GONE);

            return;
        }

        try {

            VolumeItem item =
                    HyperNovaAudioUtils.resolveVolumeItem(
                            requireContext(),
                            mMediaGroupId,
                            mVolumeItems);

            mMasterUsage =
                    resolveUsage(
                            mMediaGroupId,
                            item);

            mMasterMinimum =
                    mAudioManager.getGroupMinVolume(
                            mZoneId,
                            mMediaGroupId);

            mMasterMaximum =
                    mAudioManager.getGroupMaxVolume(
                            mZoneId,
                            mMediaGroupId);

            int current =
                    mAudioManager.getGroupVolume(
                            mZoneId,
                            mMediaGroupId);

            mMasterSectionHeader.setVisibility(
                    View.VISIBLE);

            mMasterCard.setVisibility(
                    View.VISIBLE);

            mMasterSeekBar.setMax(
                    Math.max(
                            0,
                            mMasterMaximum
                                    - mMasterMinimum));

            mMasterSeekBar.setProgress(
                    Math.max(
                            0,
                            current
                                    - mMasterMinimum));

            updateMasterPercentage(
                    current);

        } catch (RuntimeException exception) {

            mMasterSectionHeader.setVisibility(
                    View.GONE);

            mMasterCard.setVisibility(
                    View.GONE);
        }
    }

    private void updateMasterPercentage(
            int value) {

        int percentage =
                HyperNovaAudioUtils.getPercentage(
                        mMasterMinimum,
                        mMasterMaximum,
                        value);

        mMasterValue.setText(
                getString(
                        R.string.hypernova_percent,
                        percentage));
    }

    private void adjustMasterVolume(
            int delta) {

        if (mAudioManager == null
                || mMediaGroupId
                == HyperNovaAudioUtils.INVALID_GROUP_ID) {

            return;
        }

        try {

            int current =
                    mAudioManager.getGroupVolume(
                            mZoneId,
                            mMediaGroupId);

            int next =
                    Math.max(
                            mMasterMinimum,
                            Math.min(
                                    mMasterMaximum,
                                    current + delta));

            if (next == current) {
                return;
            }

            setGroupVolume(
                    mMediaGroupId,
                    mMasterUsage,
                    next,
                    /* playFeedback= */ true);

        } catch (RuntimeException ignored) {
        }
    }

    // ============================================================
    // AUDIO GROUPS
    // ============================================================

    private void bindAudioGroups(
            View root) {

        mGroupsSectionHeader =
                root.findViewById(
                        R.id.hypernova_audio_groups_section_header);

        mGroupsCard =
                root.findViewById(
                        R.id.hypernova_audio_groups_card);

        mGroupsContainer =
                root.findViewById(
                        R.id.hypernova_audio_groups_container);
    }

    private void refreshAudioGroups() {

        if (mAudioManager == null
                || mZoneId
                == CarAudioManager.INVALID_AUDIO_ZONE) {

            hideAudioGroups();

            return;
        }

        try {

            int count =
                    mAudioManager.getVolumeGroupCount(
                            mZoneId);

            if (count <= 0) {

                hideAudioGroups();

                return;
            }

            mGroupsSectionHeader.setVisibility(
                    View.VISIBLE);

            mGroupsCard.setVisibility(
                    View.VISIBLE);

            /*
             * Do not recreate sliders on every volume callback.
             *
             * Rebuild only when the platform's volume-group count
             * changes.
             */
            if (mGroupControls.size() != count) {

                buildAudioGroupRows(
                        count);
            }

            for (int groupId = 0;
                    groupId < count;
                    groupId++) {

                updateAudioGroup(
                        groupId);
            }

        } catch (RuntimeException exception) {

            hideAudioGroups();
        }
    }

    private void buildAudioGroupRows(
            int count) {

        mGroupsContainer.removeAllViews();

        mGroupControls.clear();

        for (int groupId = 0;
                groupId < count;
                groupId++) {

            if (groupId > 0) {
                addGroupDivider();
            }

            addAudioGroupRow(
                    groupId);
        }
    }

    private void addAudioGroupRow(
            int groupId) {

        View row =
                LayoutInflater.from(
                        requireContext())
                        .inflate(
                                R.layout.hypernova_sound_group_row,
                                mGroupsContainer,
                                false);

        ImageView icon =
                row.findViewById(
                        R.id.hypernova_sound_group_icon);

        TextView title =
                row.findViewById(
                        R.id.hypernova_sound_group_title);

        TextView value =
                row.findViewById(
                        R.id.hypernova_sound_group_value);

        SeekBar seekBar =
                row.findViewById(
                        R.id.hypernova_sound_group_seekbar);

        View decrease =
                row.findViewById(
                        R.id.hypernova_sound_group_decrease);

        View increase =
                row.findViewById(
                        R.id.hypernova_sound_group_increase);

        VolumeItem item =
                HyperNovaAudioUtils.resolveVolumeItem(
                        requireContext(),
                        groupId,
                        mVolumeItems);

        if (item != null) {

            title.setText(
                    item.getTitle());

            icon.setImageResource(
                    item.getIcon());

        } else {

            title.setText(
                    getString(
                            R.string.hypernova_audio_group_volume)
                            + " "
                            + (groupId + 1));

            icon.setImageResource(
                    R.drawable.hypernova_ic_volume);
        }

        int usage =
                resolveUsage(
                        groupId,
                        item);

        GroupControls controls =
                new GroupControls(
                        groupId,
                        usage,
                        seekBar,
                        value);

        mGroupControls.put(
                groupId,
                controls);

        seekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar bar,
                            int progress,
                            boolean fromUser) {

                        int groupValue =
                                controls.minimum
                                        + progress;

                        updateGroupPercentage(
                                controls,
                                groupValue);

                        if (!fromUser
                                || mRefreshing) {

                            return;
                        }

                        setGroupVolume(
                                controls.groupId,
                                controls.usage,
                                groupValue,
                                /* playFeedback= */ true);
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar bar) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar bar) {
                    }
                });

        decrease.setOnClickListener(
                clicked ->
                        adjustGroupVolume(
                                controls,
                                -1));

        increase.setOnClickListener(
                clicked ->
                        adjustGroupVolume(
                                controls,
                                1));

        /*
         * No row click listener.
         *
         * Volume is controlled here directly instead of opening
         * AudioGroupActivity.
         */

        mGroupsContainer.addView(
                row);
    }

    private void updateAudioGroup(
            int groupId) {

        GroupControls controls =
                mGroupControls.get(
                        groupId);

        if (controls == null
                || mAudioManager == null) {

            return;
        }

        try {

            controls.minimum =
                    mAudioManager.getGroupMinVolume(
                            mZoneId,
                            groupId);

            controls.maximum =
                    mAudioManager.getGroupMaxVolume(
                            mZoneId,
                            groupId);

            int current =
                    mAudioManager.getGroupVolume(
                            mZoneId,
                            groupId);

            controls.seekBar.setEnabled(
                    true);

            controls.seekBar.setMax(
                    Math.max(
                            0,
                            controls.maximum
                                    - controls.minimum));

            controls.seekBar.setProgress(
                    Math.max(
                            0,
                            current
                                    - controls.minimum));

            updateGroupPercentage(
                    controls,
                    current);

        } catch (RuntimeException exception) {

            controls.seekBar.setEnabled(
                    false);

            controls.value.setText(
                    R.string.hypernova_state_unavailable);
        }
    }

    private void updateGroupPercentage(
            GroupControls controls,
            int value) {

        int percentage =
                HyperNovaAudioUtils.getPercentage(
                        controls.minimum,
                        controls.maximum,
                        value);

        controls.value.setText(
                getString(
                        R.string.hypernova_percent,
                        percentage));
    }

    private void adjustGroupVolume(
            GroupControls controls,
            int delta) {

        if (mAudioManager == null) {
            return;
        }

        try {

            int current =
                    mAudioManager.getGroupVolume(
                            mZoneId,
                            controls.groupId);

            int next =
                    Math.max(
                            controls.minimum,
                            Math.min(
                                    controls.maximum,
                                    current + delta));

            if (next == current) {
                return;
            }

            setGroupVolume(
                    controls.groupId,
                    controls.usage,
                    next,
                    /* playFeedback= */ true);

        } catch (RuntimeException ignored) {
        }
    }

    // ============================================================
    // REAL AAOS VOLUME WRITE
    // ============================================================

    private void setGroupVolume(
            int groupId,
            int usage,
            int value,
            boolean playFeedback) {

        if (mAudioManager == null
                || mZoneId
                == CarAudioManager.INVALID_AUDIO_ZONE
                || groupId
                == HyperNovaAudioUtils.INVALID_GROUP_ID) {

            return;
        }

        try {

            int minimum =
                    mAudioManager.getGroupMinVolume(
                            mZoneId,
                            groupId);

            int maximum =
                    mAudioManager.getGroupMaxVolume(
                            mZoneId,
                            groupId);

            int safeValue =
                    Math.max(
                            minimum,
                            Math.min(
                                    maximum,
                                    value));

            /*
             * This remains the real AAOS group volume operation.
             */
            mAudioManager.setGroupVolume(
                    mZoneId,
                    groupId,
                    safeValue,
                    /* flags= */ 0);

            updateLocalVolumeUi(
                    groupId,
                    safeValue);

            if (playFeedback) {

                playVolumeFeedback(
                        groupId,
                        usage);
            }

        } catch (RuntimeException ignored) {
        }
    }

    private void updateLocalVolumeUi(
            int groupId,
            int value) {

        GroupControls controls =
                mGroupControls.get(
                        groupId);

        if (controls != null) {

            controls.seekBar.setProgress(
                    Math.max(
                            0,
                            value
                                    - controls.minimum));

            updateGroupPercentage(
                    controls,
                    value);
        }

        /*
         * Master volume currently maps to the media volume group,
         * so keep both views synchronized.
         */
        if (groupId == mMediaGroupId) {

            mMasterSeekBar.setProgress(
                    Math.max(
                            0,
                            value
                                    - mMasterMinimum));

            updateMasterPercentage(
                    value);
        }
    }

    // ============================================================
    // AOSP-LIKE AUDIO FEEDBACK
    // ============================================================

    private void playVolumeFeedback(
            int groupId,
            int usage) {

        if (mAudioManager == null
                || mRingtoneManager == null) {

            return;
        }

        try {

            /*
             * Match AOSP CarSettings behavior:
             *
             * - If dynamic routing is not active, play feedback.
             * - If dynamic routing is active, play feedback only when
             *   there is no active playback on this volume group.
             */
            boolean shouldPlay =
                    !mAudioManager.isAudioFeatureEnabled(
                            AUDIO_FEATURE_DYNAMIC_ROUTING)
                            || !mAudioManager
                            .isPlaybackOnVolumeGroupActive(
                                    mZoneId,
                                    groupId);

            if (shouldPlay) {

                mRingtoneManager.playAudioFeedback(
                        groupId,
                        usage);
            }

        } catch (RuntimeException ignored) {
        }
    }

    // ============================================================
    // USAGE RESOLUTION
    // ============================================================

    private int resolveUsage(
            int groupId,
            VolumeItem item) {

        if (item != null) {

            return item.getUsage();
        }

        if (mAudioManager != null
                && mZoneId
                != CarAudioManager.INVALID_AUDIO_ZONE) {

            try {

                int[] usages =
                        mAudioManager
                                .getUsagesForVolumeGroupId(
                                        mZoneId,
                                        groupId);

                if (usages != null
                        && usages.length > 0) {

                    return usages[0];
                }

            } catch (RuntimeException ignored) {
            }
        }

        return AudioAttributes.USAGE_MEDIA;
    }

    // ============================================================
    // EMPTY STATE
    // ============================================================

    private void hideAudioGroups() {

        mGroupsSectionHeader.setVisibility(
                View.GONE);

        mGroupsCard.setVisibility(
                View.GONE);

        mGroupControls.clear();

        mGroupsContainer.removeAllViews();
    }

    private void addGroupDivider() {

        View divider =
                new View(
                        requireContext());

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(1));

        params.setMarginStart(
                dpToPx(54));

        divider.setLayoutParams(
                params);

        divider.setBackgroundColor(
                requireContext().getColor(
                        R.color.hypernova_divider));

        mGroupsContainer.addView(
                divider);
    }

    // ============================================================
    // REFRESH
    // ============================================================

    private void refreshAudioState() {

        if (!isAdded()
                || mGroupsContainer == null) {

            return;
        }

        mAudioManager =
                HyperNovaAudioUtils.getManager(
                        requireContext());

        mZoneId =
                HyperNovaAudioUtils.getZoneId(
                        requireContext());

        mRefreshing = true;

        refreshMasterVolume();

        refreshAudioGroups();

        mRefreshing = false;
    }

    private int dpToPx(
            int dp) {

        return Math.round(
                dp
                        * getResources()
                        .getDisplayMetrics()
                        .density);
    }
}
