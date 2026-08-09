/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.android.car.settings.hypernova;

import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.hypernova.settings.R;
import com.android.car.settings.bluetooth.BluetoothUtils;
import com.android.car.settings.common.BaseFragment;
import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full-width HyperNova Bluetooth page backed by Android SettingsLib Bluetooth.
 */
public final class HyperNovaBluetoothSettingsFragment
        extends BaseFragment
        implements BluetoothCallback {

    private BluetoothAdapter mBluetoothAdapter;

    @Nullable
    private LocalBluetoothManager mLocalBluetoothManager;

    private View mStateCard;
    private Switch mStateSwitch;
    private TextView mStateSummary;

    private View mPairedSection;
    private LinearLayout mPairedContainer;

    private View mAvailableSection;
    private LinearLayout mAvailableContainer;
    private TextView mScanStatus;
    private TextView mEmptyDevices;

    private boolean mUpdatingSwitch;
    private boolean mPageStarted;

    @Override
    protected int getLayoutId() {
        return R.layout.hypernova_bluetooth_fragment;
    }

    @Override
    @StringRes
    protected int getTitleId() {
        return R.string.hypernova_bluetooth;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mBluetoothAdapter =
                BluetoothAdapter.getDefaultAdapter();

        mLocalBluetoothManager =
                BluetoothUtils.getLocalBtManager(
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
                R.string.hypernova_bluetooth,
                /* showBack= */ true);

        mStateCard =
                view.findViewById(
                        R.id.hypernova_bluetooth_state_card);

        mStateSwitch =
                view.findViewById(
                        R.id.hypernova_bluetooth_state_switch);

        mStateSummary =
                view.findViewById(
                        R.id.hypernova_bluetooth_state_summary);

        mPairedSection =
                view.findViewById(
                        R.id.hypernova_bluetooth_paired_section);

        mPairedContainer =
                view.findViewById(
                        R.id.hypernova_bluetooth_paired_container);

        mAvailableSection =
                view.findViewById(
                        R.id.hypernova_bluetooth_available_section);

        mAvailableContainer =
                view.findViewById(
                        R.id.hypernova_bluetooth_available_container);

        mScanStatus =
                view.findViewById(
                        R.id.hypernova_bluetooth_scan_status);

        mEmptyDevices =
                view.findViewById(
                        R.id.hypernova_bluetooth_empty_devices);

        mStateSwitch.setOnCheckedChangeListener(
                (button, checked) -> {

                    if (mUpdatingSwitch) {
                        return;
                    }

                    setBluetoothEnabled(
                            checked);
                });

        mStateCard.setOnClickListener(
                clicked -> {

                    if (!mStateSwitch.isEnabled()) {
                        return;
                    }

                    mStateSwitch.setChecked(
                            !mStateSwitch.isChecked());
                });

        refreshBluetoothUi();
    }

    @Override
    public void onStart() {
        super.onStart();

        mPageStarted = true;

        if (mLocalBluetoothManager != null) {

            mLocalBluetoothManager
                    .setForegroundActivity(
                            requireActivity());

            mLocalBluetoothManager
                    .getEventManager()
                    .registerCallback(this);
        }

        refreshBluetoothUi();

        if (isBluetoothOn()) {
            startScanning();
        }
    }

    @Override
    public void onStop() {

        mPageStarted = false;

        stopScanning();

        if (mLocalBluetoothManager != null) {

            mLocalBluetoothManager
                    .getEventManager()
                    .unregisterCallback(this);

            mLocalBluetoothManager
                    .setForegroundActivity(null);

            mLocalBluetoothManager
                    .getCachedDeviceManager()
                    .clearNonBondedDevices();
        }

        super.onStop();
    }

    // ============================================================
    // POWER
    // ============================================================

    private void setBluetoothEnabled(
            boolean enabled) {

        if (mBluetoothAdapter == null) {
            return;
        }

        boolean accepted;

        try {

            accepted =
                    enabled
                            ? mBluetoothAdapter.enable()
                            : mBluetoothAdapter.disable();

        } catch (RuntimeException exception) {

            accepted = false;
        }

        if (!accepted) {

            Toast.makeText(
                    requireContext(),
                    R.string.hypernova_state_unavailable,
                    Toast.LENGTH_SHORT)
                    .show();

            refreshBluetoothUi();

            return;
        }

        mStateSwitch.setEnabled(false);

        mStateSummary.setText(
                enabled
                        ? R.string.hypernova_state_turning_on
                        : R.string.hypernova_state_turning_off);
    }

    private void refreshBluetoothUi() {

        if (!isAdded()
                || mStateSwitch == null) {
            return;
        }

        if (mBluetoothAdapter == null
                || mLocalBluetoothManager == null) {

            mStateSwitch.setEnabled(false);
            mStateSwitch.setChecked(false);

            mStateSummary.setText(
                    R.string.hypernova_state_unavailable);

            hideDeviceSections();

            return;
        }

        int state =
                mBluetoothAdapter.getState();

        mUpdatingSwitch = true;

        switch (state) {

            case BluetoothAdapter.STATE_ON:

                mStateSwitch.setChecked(true);
                mStateSwitch.setEnabled(true);

                mStateSummary.setText(
                        R.string.hypernova_state_on);

                refreshDeviceLists();

                if (mPageStarted
                        && !mBluetoothAdapter.isDiscovering()
                        && !isAnyDeviceBonding()) {

                    startScanning();
                }

                break;

            case BluetoothAdapter.STATE_TURNING_ON:

                mStateSwitch.setChecked(true);
                mStateSwitch.setEnabled(false);

                mStateSummary.setText(
                        R.string.hypernova_state_turning_on);

                hideDeviceSections();

                break;

            case BluetoothAdapter.STATE_TURNING_OFF:

                mStateSwitch.setChecked(false);
                mStateSwitch.setEnabled(false);

                mStateSummary.setText(
                        R.string.hypernova_state_turning_off);

                hideDeviceSections();

                break;

            case BluetoothAdapter.STATE_OFF:
            default:

                mStateSwitch.setChecked(false);
                mStateSwitch.setEnabled(true);

                mStateSummary.setText(
                        R.string.hypernova_state_off);

                hideDeviceSections();

                break;
        }

        mUpdatingSwitch = false;
    }

    private boolean isBluetoothOn() {

        return mBluetoothAdapter != null
                && mBluetoothAdapter.getState()
                == BluetoothAdapter.STATE_ON;
    }

    // ============================================================
    // SCANNING
    // ============================================================

    private void startScanning() {

        if (!mPageStarted
                || !isBluetoothOn()
                || isAnyDeviceBonding()) {

            return;
        }

        try {

            if (mBluetoothAdapter.getScanMode()
                    != BluetoothAdapter
                    .SCAN_MODE_CONNECTABLE_DISCOVERABLE) {

                mBluetoothAdapter.setScanMode(
                        BluetoothAdapter
                                .SCAN_MODE_CONNECTABLE_DISCOVERABLE);
            }

            if (!mBluetoothAdapter.isDiscovering()) {

                mBluetoothAdapter.startDiscovery();
            }

        } catch (RuntimeException ignored) {
        }

        updateScanningState();
    }

    private void stopScanning() {

        if (mBluetoothAdapter == null) {
            return;
        }

        try {

            if (mBluetoothAdapter.isDiscovering()) {

                mBluetoothAdapter.cancelDiscovery();
            }

            if (mBluetoothAdapter.getState()
                    == BluetoothAdapter.STATE_ON) {

                mBluetoothAdapter.setScanMode(
                        BluetoothAdapter
                                .SCAN_MODE_CONNECTABLE);
            }

        } catch (RuntimeException ignored) {
        }

        updateScanningState();
    }

    private void updateScanningState() {

        if (mScanStatus == null) {
            return;
        }

        boolean scanning =
                mBluetoothAdapter != null
                        && mBluetoothAdapter.isDiscovering();

        mScanStatus.setVisibility(
                scanning
                        ? View.VISIBLE
                        : View.INVISIBLE);
    }

    private boolean isAnyDeviceBonding() {

        if (mLocalBluetoothManager == null) {
            return false;
        }

        for (CachedBluetoothDevice device :
                mLocalBluetoothManager
                        .getCachedDeviceManager()
                        .getCachedDevicesCopy()) {

            if (device.getBondState()
                    == BluetoothDevice.BOND_BONDING) {

                return true;
            }
        }

        return false;
    }

    // ============================================================
    // DEVICE LISTS
    // ============================================================

    private void refreshDeviceLists() {

        if (mLocalBluetoothManager == null
                || !isBluetoothOn()) {

            hideDeviceSections();

            return;
        }

        List<CachedBluetoothDevice> devices =
                new ArrayList<>(
                        mLocalBluetoothManager
                                .getCachedDeviceManager()
                                .getCachedDevicesCopy());

        Collections.sort(devices);

        refreshPairedDevices(
                devices);

        refreshAvailableDevices(
                devices);

        updateScanningState();
    }

    private void refreshPairedDevices(
            List<CachedBluetoothDevice> devices) {

        mPairedContainer.removeAllViews();

        int count = 0;

        for (CachedBluetoothDevice device : devices) {

            if (device.getBondState()
                    != BluetoothDevice.BOND_BONDED) {

                continue;
            }

            if (count > 0) {
                addDivider(
                        mPairedContainer);
            }

            addPairedDeviceRow(
                    device);

            count++;
        }

        mPairedSection.setVisibility(
                count > 0
                        ? View.VISIBLE
                        : View.GONE);
    }

    private void refreshAvailableDevices(
            List<CachedBluetoothDevice> devices) {

        mAvailableContainer.removeAllViews();

        int count = 0;

        for (CachedBluetoothDevice device : devices) {

            /*
             * This Android 15 branch does not expose
             * CachedBluetoothDevice.isVisible().
             *
             * Non-bonded cached devices are populated from discovery,
             * and are cleared when this page stops.
             */
            if (device.getBondState()
                    != BluetoothDevice.BOND_NONE) {

                continue;
            }

            if (count > 0) {
                addDivider(
                        mAvailableContainer);
            }

            addAvailableDeviceRow(
                    device);

            count++;
        }

        mAvailableSection.setVisibility(
                View.VISIBLE);

        mEmptyDevices.setVisibility(
                count == 0
                        ? View.VISIBLE
                        : View.GONE);
    }

    private void addPairedDeviceRow(
            CachedBluetoothDevice device) {

        View row =
                inflateDeviceRow(
                        mPairedContainer);

        bindDeviceName(
                row,
                device);

        TextView summary =
                row.findViewById(
                        R.id.hypernova_bluetooth_device_summary);

        if (device.isConnected()) {

            summary.setText(
                    R.string.hypernova_bluetooth_connected);

            summary.setTextColor(
                    requireContext().getColor(
                            R.color.hypernova_success));

        } else if (device.isBusy()) {

            summary.setText(
                    R.string.hypernova_bluetooth_connecting);

            summary.setTextColor(
                    requireContext().getColor(
                            R.color.hypernova_cyan));

        } else {

            summary.setText(
                    R.string.hypernova_bluetooth_paired);

            summary.setTextColor(
                    requireContext().getColor(
                            R.color.hypernova_text_secondary));
        }

        row.setOnClickListener(
                clicked ->
                        showDeviceDialog(device));

        mPairedContainer.addView(
                row);
    }

    private void addAvailableDeviceRow(
            CachedBluetoothDevice device) {

        View row =
                inflateDeviceRow(
                        mAvailableContainer);

        bindDeviceName(
                row,
                device);

        TextView summary =
                row.findViewById(
                        R.id.hypernova_bluetooth_device_summary);

        if (device.getBondState()
                == BluetoothDevice.BOND_BONDING) {

            summary.setText(
                    R.string.hypernova_bluetooth_pairing);

            summary.setTextColor(
                    requireContext().getColor(
                            R.color.hypernova_cyan));

        } else {

            summary.setText(
                    R.string.hypernova_bluetooth_tap_to_pair);

            summary.setTextColor(
                    requireContext().getColor(
                            R.color.hypernova_text_secondary));
        }

        row.setOnClickListener(
                clicked ->
                        startPairing(device));

        mAvailableContainer.addView(
                row);
    }

    private View inflateDeviceRow(
            LinearLayout parent) {

        return LayoutInflater.from(
                requireContext())
                .inflate(
                        R.layout.hypernova_bluetooth_device_row,
                        parent,
                        false);
    }

    private void bindDeviceName(
            View row,
            CachedBluetoothDevice device) {

        TextView title =
                row.findViewById(
                        R.id.hypernova_bluetooth_device_title);

        String name =
                device.getName();

        if (name == null
                || name.trim().isEmpty()) {

            title.setText(
                    R.string.hypernova_bluetooth_unknown_device);

        } else {

            title.setText(name);
        }
    }

    // ============================================================
    // PAIR
    // ============================================================

    private void startPairing(
            CachedBluetoothDevice device) {

        stopScanning();

        String name =
                device.getName();

        if (name == null
                || name.trim().isEmpty()) {

            name =
                    getString(
                            R.string.hypernova_bluetooth_unknown_device);
        }

        boolean started;

        try {

            started =
                    device.startPairing();

            if (started) {

                device.getDevice()
                        .setPhonebookAccessPermission(
                                BluetoothDevice.ACCESS_ALLOWED);

                device.getDevice()
                        .setMessageAccessPermission(
                                BluetoothDevice.ACCESS_ALLOWED);
            }

        } catch (RuntimeException exception) {

            started = false;
        }

        if (started) {

            Toast.makeText(
                    requireContext(),
                    getString(
                            R.string.hypernova_bluetooth_pair_started,
                            name),
                    Toast.LENGTH_SHORT)
                    .show();

        } else {

            Toast.makeText(
                    requireContext(),
                    getString(
                            R.string.hypernova_bluetooth_pair_failed,
                            name),
                    Toast.LENGTH_SHORT)
                    .show();

            startScanning();
        }

        refreshDeviceLists();
    }

    // ============================================================
    // PAIRED DEVICE DIALOG
    // ============================================================

    private void showDeviceDialog(
            CachedBluetoothDevice device) {

        Dialog dialog =
                new Dialog(
                        requireContext());

        dialog.requestWindowFeature(
                Window.FEATURE_NO_TITLE);

        dialog.setContentView(
                R.layout.hypernova_bluetooth_device_dialog);

        TextView title =
                dialog.findViewById(
                        R.id.hypernova_bluetooth_dialog_title);

        TextView summary =
                dialog.findViewById(
                        R.id.hypernova_bluetooth_dialog_summary);

        TextView connectionAction =
                dialog.findViewById(
                        R.id.hypernova_bluetooth_dialog_connection_action);

        TextView forget =
                dialog.findViewById(
                        R.id.hypernova_bluetooth_dialog_forget);

        TextView cancel =
                dialog.findViewById(
                        R.id.hypernova_bluetooth_dialog_cancel);

        String name =
                device.getName();

        if (name == null
                || name.trim().isEmpty()) {

            name =
                    getString(
                            R.string.hypernova_bluetooth_unknown_device);
        }

        title.setText(name);

        boolean connected =
                device.isConnected();

        summary.setText(
                connected
                        ? R.string.hypernova_bluetooth_connected
                        : R.string.hypernova_bluetooth_paired);

        connectionAction.setText(
                connected
                        ? R.string.hypernova_bluetooth_disconnect
                        : R.string.hypernova_bluetooth_connect);

        connectionAction.setOnClickListener(
                clicked -> {

                    try {

                        if (device.isConnected()) {

                            device.disconnect();

                        } else {

                            device.connect();
                        }

                    } catch (RuntimeException ignored) {
                    }

                    dialog.dismiss();

                    refreshDeviceLists();
                });

        forget.setOnClickListener(
                clicked -> {

                    try {

                        device.unpair();

                    } catch (RuntimeException ignored) {
                    }

                    dialog.dismiss();

                    refreshDeviceLists();

                    startScanning();
                });

        cancel.setOnClickListener(
                clicked ->
                        dialog.dismiss());

        dialog.setCanceledOnTouchOutside(
                true);

        dialog.show();

        configureDeviceDialogWindow(
                dialog);
    }

    private void configureDeviceDialogWindow(
            Dialog dialog) {

        Window window =
                dialog.getWindow();

        if (window == null) {
            return;
        }

        window.setWindowAnimations(
                R.style.HyperNovaDialogAnimation);

        window.setBackgroundDrawable(
                new ColorDrawable(
                        Color.TRANSPARENT));

        window.addFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        WindowManager.LayoutParams params =
                window.getAttributes();

        params.dimAmount =
                0.68f;

        window.setAttributes(
                params);

        int parentWidth =
                requireActivity()
                        .getWindow()
                        .getDecorView()
                        .getWidth();

        if (parentWidth <= 0) {

            parentWidth =
                    getResources()
                            .getDisplayMetrics()
                            .widthPixels;
        }

        window.setLayout(
                Math.max(
                        dpToPx(320),
                        parentWidth
                                - dpToPx(48)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ============================================================
    // CALLBACKS
    // ============================================================

    @Override
    public void onBluetoothStateChanged(
            int bluetoothState) {

        refreshBluetoothUi();
    }

    @Override
    public void onScanningStateChanged(
            boolean started) {

        updateScanningState();

        refreshDeviceLists();

        if (!started
                && mPageStarted
                && isBluetoothOn()
                && !isAnyDeviceBonding()) {

            startScanning();
        }
    }

    @Override
    public void onDeviceAdded(
            @NonNull CachedBluetoothDevice device) {

        refreshDeviceLists();
    }

    @Override
    public void onDeviceDeleted(
            @NonNull CachedBluetoothDevice device) {

        refreshDeviceLists();
    }

    @Override
    public void onDeviceBondStateChanged(
            @NonNull CachedBluetoothDevice device,
            int bondState) {

        refreshDeviceLists();

        if (bondState
                != BluetoothDevice.BOND_BONDING) {

            startScanning();
        }
    }

    @Override
    public void onConnectionStateChanged(
            @Nullable CachedBluetoothDevice device,
            int state) {

        refreshDeviceLists();
    }

    @Override
    public void onProfileConnectionStateChanged(
            @NonNull CachedBluetoothDevice device,
            int state,
            int bluetoothProfile) {

        refreshDeviceLists();
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private void hideDeviceSections() {

        mPairedContainer.removeAllViews();

        mAvailableContainer.removeAllViews();

        mPairedSection.setVisibility(
                View.GONE);

        mAvailableSection.setVisibility(
                View.GONE);
    }

    private void addDivider(
            LinearLayout container) {

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

        container.addView(
                divider);
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
