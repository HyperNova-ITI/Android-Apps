package com.hypernova.media.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;

import com.hypernova.media.model.MediaItemModel;
import com.hypernova.media.model.MediaSnapshot;
import com.hypernova.media.model.MediaSourceType;
import com.hypernova.media.source.PlatformBrowserSource;

import java.util.List;

/** Adapter for the platform A2DP sink / AVRCP controller MediaBrowserService. */
public final class BluetoothSource extends PlatformBrowserSource {
    public static final ComponentName COMPONENT = new ComponentName(
            "com.android.bluetooth",
            "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService");

    private final BluetoothAdapter mAdapter;
    private BluetoothProfile mA2dpSink;
    private String mConnectedDeviceName;
    private boolean mReceiverRegistered;
    private boolean mActive;
    private boolean mProfileRequestPending;

    public BluetoothSource(Context context) {
        super(context, MediaSourceType.BLUETOOTH, COMPONENT);
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        mAdapter = manager == null ? null : manager.getAdapter();
    }

    @Override
    protected MediaSnapshot.State initialState() {
        return mContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                ? MediaSnapshot.State.IDLE : MediaSnapshot.State.PERMISSION_REQUIRED;
    }

    @Override
    public void activate() {
        mActive = true;
        registerReceiver();
        requestProfile();
        if (!hasPermission() || mAdapter == null || !mAdapter.isEnabled()) {
            super.activate();
            return;
        }
        super.activate();
    }

    @Override
    public void deactivate() {
        mActive = false;
        super.deactivate();
        closeProfile();
        if (mReceiverRegistered) {
            mContext.unregisterReceiver(mReceiver);
            mReceiverRegistered = false;
        }
    }

    @Override
    protected String getDeviceName() {
        return mConnectedDeviceName;
    }

    @Override
    protected MediaSnapshot.State mapState(android.media.session.PlaybackState playback) {
        if (!hasPermission()) {
            return MediaSnapshot.State.PERMISSION_REQUIRED;
        }
        if (mAdapter == null || !mAdapter.isEnabled()) {
            return MediaSnapshot.State.UNAVAILABLE;
        }
        if (mConnectedDeviceName == null) {
            return MediaSnapshot.State.DISCONNECTED;
        }
        return super.mapState(playback);
    }

    @Override
    protected MediaSnapshot.State errorPlaybackState() {
        return mConnectedDeviceName == null
                ? MediaSnapshot.State.DISCONNECTED : MediaSnapshot.State.ERROR;
    }

    @Override
    protected MediaSnapshot.State disconnectedState() {
        return MediaSnapshot.State.DISCONNECTED;
    }

    private void registerReceiver() {
        if (mReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
        mReceiverRegistered = true;
    }

    private void requestProfile() {
        if (mAdapter == null || !hasPermission() || mA2dpSink != null
                || mProfileRequestPending) {
            return;
        }
        try {
            mProfileRequestPending = mAdapter.getProfileProxy(
                    mContext, mProfileListener, BluetoothProfile.A2DP_SINK);
        } catch (RuntimeException ignored) {
            mProfileRequestPending = false;
            mA2dpSink = null;
        }
    }

    private void closeProfile() {
        if (mAdapter != null && mA2dpSink != null) {
            try {
                mAdapter.closeProfileProxy(BluetoothProfile.A2DP_SINK, mA2dpSink);
            } catch (RuntimeException ignored) {
            }
        }
        mProfileRequestPending = false;
        mA2dpSink = null;
        mConnectedDeviceName = null;
    }

    private boolean hasPermission() {
        return mContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void updateConnectedDevice() {
        mConnectedDeviceName = null;
        if (mA2dpSink == null || !hasPermission()) {
            refreshFromController();
            return;
        }
        try {
            List<BluetoothDevice> devices = mA2dpSink.getConnectedDevices();
            if (!devices.isEmpty()) {
                String alias = devices.get(0).getAlias();
                mConnectedDeviceName = alias == null ? devices.get(0).getName() : alias;
            }
        } catch (RuntimeException ignored) {
            mConnectedDeviceName = null;
        }
        refreshFromController();
    }

    @Override
    public void retry() {
        if (mActive && hasPermission() && mAdapter != null && mAdapter.isEnabled()) {
            requestProfile();
        }
        super.retry();
    }

    @Override
    protected MediaSnapshot createSnapshot(MediaMetadata metadata, PlaybackState playback,
            List<MediaItemModel> items) {
        if (!hasPermission()) {
            return MediaSnapshot.builder(MediaSourceType.BLUETOOTH,
                    MediaSnapshot.State.PERMISSION_REQUIRED).build();
        }
        if (mAdapter == null || !mAdapter.isEnabled()) {
            return MediaSnapshot.builder(MediaSourceType.BLUETOOTH,
                    MediaSnapshot.State.UNAVAILABLE).build();
        }
        if (mConnectedDeviceName == null) {
            return MediaSnapshot.builder(MediaSourceType.BLUETOOTH,
                    MediaSnapshot.State.DISCONNECTED).build();
        }
        return super.createSnapshot(metadata, playback, items);
    }

    private final BluetoothProfile.ServiceListener mProfileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP_SINK) {
                        mProfileRequestPending = false;
                        mA2dpSink = proxy;
                        updateConnectedDevice();
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.A2DP_SINK) {
                        mProfileRequestPending = false;
                        mA2dpSink = null;
                        mConnectedDeviceName = null;
                        refreshFromController();
                    }
                }
            };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                closeProfile();
                if (mActive && mAdapter != null && mAdapter.isEnabled()) {
                    requestProfile();
                    retry();
                } else {
                    refreshFromController();
                }
            } else {
                updateConnectedDevice();
            }
        }
    };

    @Override
    public void release() {
        deactivate();
        super.release();
    }
}
