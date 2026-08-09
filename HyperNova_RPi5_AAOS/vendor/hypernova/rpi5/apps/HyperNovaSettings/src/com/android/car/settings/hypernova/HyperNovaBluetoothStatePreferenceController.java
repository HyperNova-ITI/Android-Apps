/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.car.settings.hypernova;

import android.bluetooth.BluetoothAdapter;
import android.car.drivingstate.CarUxRestrictions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.hypernova.settings.R;
import com.android.car.settings.bluetooth.BluetoothStateSwitchPreferenceController;
import com.android.car.settings.common.ColoredSwitchPreference;
import com.android.car.settings.common.FragmentController;

/**
 * Presentation-only state label layered over the official Bluetooth power/policy controller.
 */
public final class HyperNovaBluetoothStatePreferenceController
        extends BluetoothStateSwitchPreferenceController {

    private final BroadcastReceiver mPresentationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshUi();
        }
    };
    private boolean mReceiverRegistered;

    public HyperNovaBluetoothStatePreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions) {
        super(context, preferenceKey, fragmentController, uxRestrictions);
    }

    @Override
    protected void updateState(ColoredSwitchPreference preference) {
        super.updateState(preference);
        switch (BluetoothAdapter.getDefaultAdapter().getState()) {
            case BluetoothAdapter.STATE_ON:
                preference.setSummary(R.string.hypernova_state_on);
                break;
            case BluetoothAdapter.STATE_TURNING_ON:
                preference.setSummary(R.string.hypernova_state_turning_on);
                break;
            case BluetoothAdapter.STATE_TURNING_OFF:
                preference.setSummary(R.string.hypernova_state_turning_off);
                break;
            case BluetoothAdapter.STATE_OFF:
            default:
                preference.setSummary(R.string.hypernova_state_off);
                break;
        }
    }

    @Override
    protected void onStartInternal() {
        super.onStartInternal();
        getContext().registerReceiver(mPresentationReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        mReceiverRegistered = true;
    }

    @Override
    protected void onStopInternal() {
        if (mReceiverRegistered) {
            getContext().unregisterReceiver(mPresentationReceiver);
            mReceiverRegistered = false;
        }
        super.onStopInternal();
    }
}
