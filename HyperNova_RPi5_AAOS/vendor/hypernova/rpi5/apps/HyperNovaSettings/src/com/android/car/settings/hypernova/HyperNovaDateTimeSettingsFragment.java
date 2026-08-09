/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.android.car.settings.hypernova;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.hypernova.settings.R;
import com.android.car.settings.common.BaseFragment;
import com.android.car.settings.datetime.DatePickerFragment;
import com.android.car.settings.datetime.TimePickerFragment;
import com.android.car.settings.datetime.TimeZonePickerScreenFragment;

import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Full-width HyperNova Date & Time screen.
 *
 * The screen owns presentation only. Date, time and time-zone selection
 * continue through the official CarSettings picker fragments.
 */
public final class HyperNovaDateTimeSettingsFragment extends BaseFragment {

    private View mAutoDateTimeCard;
    private Switch mAutoDateTimeSwitch;
    private TextView mAutoDateTimeSummary;

    private View mAutoTimeZoneCard;
    private Switch mAutoTimeZoneSwitch;
    private TextView mAutoTimeZoneSummary;

    private View mTimeZoneRow;
    private TextView mTimeZoneTitle;
    private TextView mTimeZoneSummary;

    private View mDateRow;
    private TextView mDateTitle;
    private TextView mDateSummary;

    private View mTimeRow;
    private TextView mTimeTitle;
    private TextView mTimeSummary;

    private View mTimeFormatRow;
    private TextView mTimeFormatTitle;
    private TextView mTimeFormatSummary;

    private TextView mLiveTimeZone;

    private boolean mRefreshing;

    private final BroadcastReceiver mTimeReceiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(
                        Context context,
                        Intent intent) {

                    refreshAll();
                }
            };

    @Override
    protected int getLayoutId() {
        return R.layout.hypernova_datetime_fragment;
    }

    @Override
    @StringRes
    protected int getTitleId() {
        return R.string.hypernova_date_time;
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
                R.string.hypernova_date_time,
                /* showBack= */ true);

        mLiveTimeZone =
                view.findViewById(
                        R.id.hypernova_datetime_live_zone);

        bindAutomaticDateTime(view);
        bindAutomaticTimeZone(view);

        bindTimeZoneRow(view);
        bindDateRow(view);
        bindTimeRow(view);
        bindTimeFormatRow(view);

        refreshAll();
    }

    @Override
    public void onStart() {
        super.onStart();

        IntentFilter filter =
                new IntentFilter();

        filter.addAction(
                Intent.ACTION_TIME_CHANGED);

        filter.addAction(
                Intent.ACTION_TIMEZONE_CHANGED);

        filter.addAction(
                Intent.ACTION_DATE_CHANGED);

        filter.addAction(
                Intent.ACTION_TIME_TICK);

        requireContext().registerReceiver(
                mTimeReceiver,
                filter);

        refreshAll();
    }

    @Override
    public void onStop() {

        requireContext().unregisterReceiver(
                mTimeReceiver);

        super.onStop();
    }

    // ============================================================
    // AUTOMATIC DATE & TIME
    // ============================================================

    private void bindAutomaticDateTime(
            View root) {

        mAutoDateTimeCard =
                root.findViewById(
                        R.id.hypernova_auto_datetime_card);

        mAutoDateTimeSwitch =
                root.findViewById(
                        R.id.hypernova_auto_datetime_switch);

        mAutoDateTimeSummary =
                root.findViewById(
                        R.id.hypernova_auto_datetime_summary);

        mAutoDateTimeSwitch.setOnCheckedChangeListener(
                (button, checked) -> {

                    if (mRefreshing) {
                        return;
                    }

                    setAutomaticDateTime(
                            checked);
                });

        mAutoDateTimeCard.setOnClickListener(
                clicked -> {

                    if (!mAutoDateTimeSwitch.isEnabled()) {
                        return;
                    }

                    mAutoDateTimeSwitch.setChecked(
                            !mAutoDateTimeSwitch.isChecked());
                });
    }

    private void setAutomaticDateTime(
            boolean enabled) {

        Settings.Global.putInt(
                requireContext().getContentResolver(),
                Settings.Global.AUTO_TIME,
                enabled ? 1 : 0);

        requireContext().sendBroadcast(
                new Intent(
                        Intent.ACTION_TIME_CHANGED));

        refreshAll();
    }

    private boolean isAutomaticDateTimeEnabled() {

        return Settings.Global.getInt(
                requireContext().getContentResolver(),
                Settings.Global.AUTO_TIME,
                0) > 0;
    }

    // ============================================================
    // AUTOMATIC TIME ZONE
    // ============================================================

    private void bindAutomaticTimeZone(
            View root) {

        mAutoTimeZoneCard =
                root.findViewById(
                        R.id.hypernova_auto_timezone_card);

        mAutoTimeZoneSwitch =
                root.findViewById(
                        R.id.hypernova_auto_timezone_switch);

        mAutoTimeZoneSummary =
                root.findViewById(
                        R.id.hypernova_auto_timezone_summary);

        mAutoTimeZoneSwitch.setOnCheckedChangeListener(
                (button, checked) -> {

                    if (mRefreshing) {
                        return;
                    }

                    setAutomaticTimeZone(
                            checked);
                });

        mAutoTimeZoneCard.setOnClickListener(
                clicked -> {

                    if (!mAutoTimeZoneSwitch.isEnabled()) {
                        return;
                    }

                    mAutoTimeZoneSwitch.setChecked(
                            !mAutoTimeZoneSwitch.isChecked());
                });
    }

    private void setAutomaticTimeZone(
            boolean enabled) {

        Settings.Global.putInt(
                requireContext().getContentResolver(),
                Settings.Global.AUTO_TIME_ZONE,
                enabled ? 1 : 0);

        requireContext().sendBroadcast(
                new Intent(
                        Intent.ACTION_TIME_CHANGED));

        refreshAll();
    }

    private boolean isAutomaticTimeZoneEnabled() {

        return Settings.Global.getInt(
                requireContext().getContentResolver(),
                Settings.Global.AUTO_TIME_ZONE,
                0) > 0;
    }

    // ============================================================
    // TIME ZONE
    // ============================================================

    private void bindTimeZoneRow(
            View root) {

        mTimeZoneRow =
                root.findViewById(
                        R.id.hypernova_timezone_row);

        mTimeZoneTitle =
                mTimeZoneRow.findViewById(
                        R.id.hypernova_datetime_row_title);

        mTimeZoneSummary =
                mTimeZoneRow.findViewById(
                        R.id.hypernova_datetime_row_summary);

        mTimeZoneTitle.setText(
                R.string.hypernova_time_zone);

        mTimeZoneRow.setOnClickListener(
                clicked -> {

                    if (!mTimeZoneRow.isEnabled()) {
                        return;
                    }

                    getFragmentHost().launchFragment(
                            new TimeZonePickerScreenFragment());
                });
    }

    // ============================================================
    // DATE
    // ============================================================

    private void bindDateRow(
            View root) {

        mDateRow =
                root.findViewById(
                        R.id.hypernova_date_row);

        mDateTitle =
                mDateRow.findViewById(
                        R.id.hypernova_datetime_row_title);

        mDateSummary =
                mDateRow.findViewById(
                        R.id.hypernova_datetime_row_summary);

        mDateTitle.setText(
                R.string.hypernova_date);

        mDateRow.setOnClickListener(
                clicked -> {

                    if (!mDateRow.isEnabled()) {
                        return;
                    }

                    getFragmentHost().launchFragment(
                            new DatePickerFragment());
                });
    }

    // ============================================================
    // TIME
    // ============================================================

    private void bindTimeRow(
            View root) {

        mTimeRow =
                root.findViewById(
                        R.id.hypernova_time_row);

        mTimeTitle =
                mTimeRow.findViewById(
                        R.id.hypernova_datetime_row_title);

        mTimeSummary =
                mTimeRow.findViewById(
                        R.id.hypernova_datetime_row_summary);

        mTimeTitle.setText(
                R.string.hypernova_time);

        mTimeRow.setOnClickListener(
                clicked -> {

                    if (!mTimeRow.isEnabled()) {
                        return;
                    }

                    getFragmentHost().launchFragment(
                            new TimePickerFragment());
                });
    }

    // ============================================================
    // TIME FORMAT
    // ============================================================

    private void bindTimeFormatRow(
            View root) {

        mTimeFormatRow =
                root.findViewById(
                        R.id.hypernova_time_format_row);

        mTimeFormatTitle =
                mTimeFormatRow.findViewById(
                        R.id.hypernova_datetime_row_title);

        mTimeFormatSummary =
                mTimeFormatRow.findViewById(
                        R.id.hypernova_datetime_row_summary);

        mTimeFormatTitle.setText(
                R.string.hypernova_time_format);

        mTimeFormatRow.setOnClickListener(
                clicked ->
                        toggleTimeFormat());
    }

    private void toggleTimeFormat() {

        boolean use24Hour =
                DateFormat.is24HourFormat(
                        requireContext());

        boolean newUse24Hour =
                !use24Hour;

        Settings.System.putString(
                requireContext().getContentResolver(),
                Settings.System.TIME_12_24,
                newUse24Hour
                        ? "24"
                        : "12");

        Intent timeChanged =
                new Intent(
                        Intent.ACTION_TIME_CHANGED);

        timeChanged.putExtra(
                Intent.EXTRA_TIME_PREF_24_HOUR_FORMAT,
                newUse24Hour
                        ? Intent.EXTRA_TIME_PREF_VALUE_USE_24_HOUR
                        : Intent.EXTRA_TIME_PREF_VALUE_USE_12_HOUR);

        requireContext().sendBroadcast(
                timeChanged);

        refreshAll();
    }

    // ============================================================
    // REFRESH
    // ============================================================

    private void refreshAll() {

        if (!isAdded()
                || mAutoDateTimeSwitch == null) {

            return;
        }

        mRefreshing = true;

        boolean automaticDateTime =
                isAutomaticDateTimeEnabled();

        boolean automaticTimeZone =
                isAutomaticTimeZoneEnabled();

        mAutoDateTimeSwitch.setChecked(
                automaticDateTime);

        mAutoDateTimeSummary.setText(
                automaticDateTime
                        ? R.string.hypernova_state_on
                        : R.string.hypernova_state_off);

        mAutoTimeZoneSwitch.setChecked(
                automaticTimeZone);

        mAutoTimeZoneSummary.setText(
                automaticTimeZone
                        ? R.string.hypernova_state_on
                        : R.string.hypernova_state_off);

        /*
         * Same user-facing behavior as the stock Date & Time screen:
         * manual date/time are unavailable when automatic time is active.
         */
        setRowEnabled(
                mDateRow,
                !automaticDateTime);

        setRowEnabled(
                mTimeRow,
                !automaticDateTime);

        /*
         * Manual timezone selection is unavailable while automatic
         * timezone detection is active.
         */
        setRowEnabled(
                mTimeZoneRow,
                !automaticTimeZone);

        refreshDateTimeValues();

        mRefreshing = false;
    }

    private void refreshDateTimeValues() {

        Date now =
                new Date();

        mDateSummary.setText(
                DateFormat.getMediumDateFormat(
                        requireContext())
                        .format(now));

        mTimeSummary.setText(
                DateFormat.getTimeFormat(
                        requireContext())
                        .format(now));

        mTimeFormatSummary.setText(
                DateFormat.is24HourFormat(
                        requireContext())
                        ? R.string.hypernova_time_format_24
                        : R.string.hypernova_time_format_12);

        String timeZoneSummary =
                buildTimeZoneSummary();

        mTimeZoneSummary.setText(
                timeZoneSummary);

        if (mLiveTimeZone != null) {
            mLiveTimeZone.setText(
                    timeZoneSummary);
        }
    }

    private String buildTimeZoneSummary() {

        TimeZone timeZone =
                TimeZone.getDefault();

        int offsetMillis =
                timeZone.getOffset(
                        System.currentTimeMillis());

        int totalMinutes =
                Math.abs(
                        offsetMillis
                                / 60000);

        int hours =
                totalMinutes / 60;

        int minutes =
                totalMinutes % 60;

        String sign =
                offsetMillis >= 0
                        ? "+"
                        : "-";

        String id =
                timeZone.getID();

        String city =
                id;

        int separator =
                id.lastIndexOf('/');

        if (separator >= 0
                && separator < id.length() - 1) {

            city =
                    id.substring(
                            separator + 1);
        }

        city =
                city.replace(
                        '_',
                        ' ');

        return String.format(
                Locale.getDefault(),
                "(UTC%s%02d:%02d) %s",
                sign,
                hours,
                minutes,
                city);
    }

    private void setRowEnabled(
            View row,
            boolean enabled) {

        row.setEnabled(
                enabled);

        row.setClickable(
                enabled);

        row.setAlpha(
                enabled
                        ? 1.0f
                        : 0.42f);
    }
}
