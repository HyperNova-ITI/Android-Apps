/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.settings.hypernova;

import static android.car.media.CarAudioManager.AUDIO_FEATURE_VOLUME_GROUP_EVENTS;
import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX;
import static com.android.settingslib.display.BrightnessUtils.convertLinearToGamma;

import android.bluetooth.BluetoothAdapter;
import android.car.media.CarAudioManager;
import android.car.media.CarVolumeGroupEvent;
import android.car.media.CarVolumeGroupEventCallback;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.android.car.settings.CarSettingsApplication;
import com.hypernova.settings.R;
import com.android.car.settings.bluetooth.BluetoothUtils;
import com.android.car.settings.common.BaseFragment;
import com.android.car.settings.wifi.CarWifiManager;
import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.wifitrackerlib.WifiEntry;

import java.util.List;

/**
 * HyperNova's restricted-scope Settings home.
 *
 * <p>This class owns only navigation and read-only summary presentation. Platform mutations and
 * subordinate flows remain in the official CarSettings controllers and helpers.</p>
 */
public final class HyperNovaHomepageFragment extends BaseFragment implements
        CarWifiManager.Listener, BluetoothCallback {

    private static final Uri BRIGHTNESS_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);

    private CarWifiManager mCarWifiManager;
    @Nullable
    private LocalBluetoothManager mLocalBluetoothManager;
    private ContentObserver mBrightnessObserver;
    private boolean mAudioCallbackRegistered;
    private boolean mUsingVolumeGroupEvents;

    private TextView mWifiStatus;
    private TextView mBluetoothStatus;
    private TextView mBrightnessStatus;
    private TextView mVolumeStatus;

    private final CarAudioManager.CarVolumeCallback mCarVolumeCallback =
            new CarAudioManager.CarVolumeCallback() {
                @Override
                public void onGroupVolumeChanged(int zoneId, int groupId, int flags) {
                    updateVolumeStatus();
                }

                @Override
                public void onMasterMuteChanged(int zoneId, int flags) {
                    updateVolumeStatus();
                }

                @Override
                public void onGroupMuteChanged(int zoneId, int groupId, int flags) {
                    updateVolumeStatus();
                }
            };

    private final CarVolumeGroupEventCallback mCarVolumeGroupEventCallback =
            new CarVolumeGroupEventCallback() {
                @Override
                public void onVolumeGroupEvent(List<CarVolumeGroupEvent> volumeGroupEvents) {
                    updateVolumeStatus();
                }
            };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mCarWifiManager = new CarWifiManager(requireContext(), getLifecycle());
        mLocalBluetoothManager = BluetoothUtils.getLocalBtManager(requireContext());
        mBrightnessObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                updateBrightnessStatus();
            }
        };
    }

    @Override
    protected int getLayoutId() {
        return R.layout.hypernova_homepage_fragment;
    }

    @Override
    @StringRes
    protected int getTitleId() {
        return R.string.hypernova_app_label;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        HyperNovaSettingsActivities.configureHeader(
                this, R.string.hypernova_settings, /* showBack= */ false);

        mWifiStatus = configureStatusCard(view, R.id.hypernova_wifi_status,
                R.drawable.hypernova_ic_wifi, R.string.hypernova_wifi, v -> launchWifi());
        mBluetoothStatus = configureStatusCard(view, R.id.hypernova_bluetooth_status,
                R.drawable.hypernova_ic_bluetooth, R.string.hypernova_bluetooth,
                v -> launchBluetooth());
        mBrightnessStatus = configureStatusCard(view, R.id.hypernova_brightness_status,
                R.drawable.hypernova_ic_display, R.string.hypernova_brightness,
                v -> launchDisplay());
        mVolumeStatus = configureStatusCard(view, R.id.hypernova_volume_status,
                R.drawable.hypernova_ic_volume, R.string.hypernova_volume, v -> launchSound());

        configureRow(view, R.id.hypernova_display_row, R.drawable.hypernova_ic_display,
                R.string.hypernova_display, R.string.hypernova_display_summary,
                v -> launchDisplay());
        configureRow(view, R.id.hypernova_sound_row, R.drawable.hypernova_ic_volume,
                R.string.hypernova_sound, R.string.hypernova_sound_summary, v -> launchSound());
        configureRow(view, R.id.hypernova_wifi_row, R.drawable.hypernova_ic_wifi,
                R.string.hypernova_wifi_network, R.string.hypernova_wifi_summary,
                v -> launchWifi());
        configureRow(view, R.id.hypernova_bluetooth_row, R.drawable.hypernova_ic_bluetooth,
                R.string.hypernova_bluetooth, R.string.hypernova_bluetooth_summary,
                v -> launchBluetooth());
        configureRow(view, R.id.hypernova_date_time_row, R.drawable.hypernova_ic_time,
                R.string.hypernova_date_time, R.string.hypernova_date_time_summary,
                v -> startActivity(new Intent(requireContext(),
                        HyperNovaSettingsActivities.DateTimeActivity.class)));
    }

    @Override
    public void onStart() {
        super.onStart();
        mCarWifiManager.addListener(this);
        if (mLocalBluetoothManager != null) {
            mLocalBluetoothManager.getEventManager().registerCallback(this);
        }
        requireContext().getContentResolver().registerContentObserver(BRIGHTNESS_URI,
                /* notifyForDescendants= */ false, mBrightnessObserver);
        registerAudioCallback();
        updateAllStatus();
    }

    @Override
    public void onStop() {
        mCarWifiManager.removeListener(this);
        if (mLocalBluetoothManager != null) {
            mLocalBluetoothManager.getEventManager().unregisterCallback(this);
        }
        requireContext().getContentResolver().unregisterContentObserver(mBrightnessObserver);
        unregisterAudioCallback();
        super.onStop();
    }

    @Override
    public void onWifiEntriesChanged() {
        updateWifiStatus();
    }

    @Override
    public void onWifiStateChanged(int state) {
        updateWifiStatus();
    }

    @Override
    public void onBluetoothStateChanged(int bluetoothState) {
        updateBluetoothStatus();
    }

    @Override
    public void onDeviceAdded(@NonNull CachedBluetoothDevice cachedDevice) {
        updateBluetoothStatus();
    }

    @Override
    public void onDeviceDeleted(@NonNull CachedBluetoothDevice cachedDevice) {
        updateBluetoothStatus();
    }

    @Override
    public void onDeviceBondStateChanged(@NonNull CachedBluetoothDevice cachedDevice,
            int bondState) {
        updateBluetoothStatus();
    }

    @Override
    public void onConnectionStateChanged(@Nullable CachedBluetoothDevice cachedDevice, int state) {
        updateBluetoothStatus();
    }

    @Override
    public void onProfileConnectionStateChanged(@NonNull CachedBluetoothDevice cachedDevice,
            int state, int bluetoothProfile) {
        updateBluetoothStatus();
    }

    private TextView configureStatusCard(View root, int cardId, int iconId,
            @StringRes int titleId, View.OnClickListener listener) {
        View card = root.findViewById(cardId);
        ((ImageView) card.findViewById(R.id.hypernova_status_icon)).setImageResource(iconId);
        ((TextView) card.findViewById(R.id.hypernova_status_title)).setText(titleId);
        card.setOnClickListener(listener);
        return card.findViewById(R.id.hypernova_status_value);
    }

    private void configureRow(View root, int rowId, int iconId, @StringRes int titleId,
            @StringRes int subtitleId, View.OnClickListener listener) {
        View row = root.findViewById(rowId);
        ((ImageView) row.findViewById(R.id.hypernova_row_icon)).setImageResource(iconId);
        ((TextView) row.findViewById(R.id.hypernova_row_title)).setText(titleId);
        ((TextView) row.findViewById(R.id.hypernova_row_subtitle)).setText(subtitleId);
        row.setOnClickListener(listener);
    }

    private void launchWifi() {
        startActivity(new Intent(requireContext(), HyperNovaSettingsActivities.WifiActivity.class));
    }

    private void launchBluetooth() {
        startActivity(new Intent(requireContext(),
                HyperNovaSettingsActivities.BluetoothActivity.class));
    }

    private void launchDisplay() {
        startActivity(new Intent(requireContext(),
                HyperNovaSettingsActivities.DisplayActivity.class));
    }

    private void launchSound() {
        startActivity(new Intent(requireContext(), HyperNovaSettingsActivities.SoundActivity.class));
    }

    private void updateAllStatus() {
        updateWifiStatus();
        updateBluetoothStatus();
        updateBrightnessStatus();
        updateVolumeStatus();
    }

    private void updateWifiStatus() {
        if (mWifiStatus == null) {
            return;
        }
        switch (mCarWifiManager.getWifiState()) {
            case WifiManager.WIFI_STATE_ENABLED:
                List<WifiEntry> connectedEntries = mCarWifiManager.getConnectedWifiEntries();
                if (connectedEntries.isEmpty()) {
                    mWifiStatus.setText(R.string.hypernova_state_on);
                } else {
                    mWifiStatus.setText(getString(R.string.hypernova_state_connected_to,
                            connectedEntries.get(0).getTitle()));
                }
                break;
            case WifiManager.WIFI_STATE_DISABLED:
                mWifiStatus.setText(R.string.hypernova_state_off);
                break;
            case WifiManager.WIFI_STATE_ENABLING:
                mWifiStatus.setText(R.string.hypernova_state_turning_on);
                break;
            case WifiManager.WIFI_STATE_DISABLING:
                mWifiStatus.setText(R.string.hypernova_state_turning_off);
                break;
            default:
                mWifiStatus.setText(R.string.hypernova_state_unavailable);
        }
    }

    private void updateBluetoothStatus() {
        if (mBluetoothStatus == null) {
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || mLocalBluetoothManager == null) {
            mBluetoothStatus.setText(R.string.hypernova_state_unavailable);
            return;
        }
        switch (adapter.getState()) {
            case BluetoothAdapter.STATE_ON:
                for (CachedBluetoothDevice device : mLocalBluetoothManager
                        .getCachedDeviceManager().getCachedDevicesCopy()) {
                    if (device.isConnected()) {
                        mBluetoothStatus.setText(getString(
                                R.string.hypernova_state_connected_to, device.getName()));
                        return;
                    }
                }
                mBluetoothStatus.setText(R.string.hypernova_state_on);
                break;
            case BluetoothAdapter.STATE_OFF:
                mBluetoothStatus.setText(R.string.hypernova_state_off);
                break;
            case BluetoothAdapter.STATE_TURNING_ON:
                mBluetoothStatus.setText(R.string.hypernova_state_turning_on);
                break;
            case BluetoothAdapter.STATE_TURNING_OFF:
                mBluetoothStatus.setText(R.string.hypernova_state_turning_off);
                break;
            default:
                mBluetoothStatus.setText(R.string.hypernova_state_unavailable);
        }
    }

    private void updateBrightnessStatus() {
        if (mBrightnessStatus == null) {
            return;
        }
        PowerManager powerManager = requireContext().getSystemService(PowerManager.class);
        try {
            int linear = Settings.System.getIntForUser(requireContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, UserHandle.myUserId());
            int gamma = convertLinearToGamma(linear,
                    powerManager.getMinimumScreenBrightnessSetting(),
                    powerManager.getMaximumScreenBrightnessSetting());
            int percentage = Math.round(gamma * 100f / GAMMA_SPACE_MAX);
            mBrightnessStatus.setText(getString(R.string.hypernova_percent, percentage));
        } catch (Settings.SettingNotFoundException | RuntimeException e) {
            mBrightnessStatus.setText(R.string.hypernova_state_unavailable);
        }
    }

    private void updateVolumeStatus() {
        if (mVolumeStatus == null) {
            return;
        }

        HyperNovaAudioBackend backend =
                HyperNovaAudioBackend.create(requireContext());

        if (!backend.isAvailable()) {
            mVolumeStatus.setText(
                    R.string.hypernova_state_unavailable);
            return;
        }

        mVolumeStatus.setText(getString(
                R.string.hypernova_percent,
                backend.getPercentage()));
    }

    private void registerAudioCallback() {
        CarAudioManager manager = getCarAudioManager();
        if (manager == null) {
            return;
        }
        try {
            mUsingVolumeGroupEvents =
                    manager.isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_EVENTS);
            if (mUsingVolumeGroupEvents) {
                manager.registerCarVolumeGroupEventCallback(
                        requireContext().getMainExecutor(), mCarVolumeGroupEventCallback);
            } else {
                manager.registerCarVolumeCallback(mCarVolumeCallback);
            }
            mAudioCallbackRegistered = true;
        } catch (RuntimeException e) {
            mAudioCallbackRegistered = false;
        }
    }

    private void unregisterAudioCallback() {
        if (!mAudioCallbackRegistered) {
            return;
        }
        CarAudioManager manager = getCarAudioManager();
        if (manager != null) {
            try {
                if (mUsingVolumeGroupEvents) {
                    manager.unregisterCarVolumeGroupEventCallback(mCarVolumeGroupEventCallback);
                } else {
                    manager.unregisterCarVolumeCallback(mCarVolumeCallback);
                }
            } catch (RuntimeException e) {
                // Car service teardown races are expected while the activity is stopping.
            }
        }
        mAudioCallbackRegistered = false;
    }

    @Nullable
    private CarAudioManager getCarAudioManager() {
        return ((CarSettingsApplication) requireContext().getApplicationContext())
                .getCarAudioManager();
    }
}
