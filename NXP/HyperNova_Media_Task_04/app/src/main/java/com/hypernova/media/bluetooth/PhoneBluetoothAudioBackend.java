package com.hypernova.media.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.hypernova.media.model.BluetoothUiState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reports the phone connected to Android Automotive through A2DP Sink and
 * reads the real AVRCP metadata exposed by BluetoothMediaBrowserService.
 */
public final class PhoneBluetoothAudioBackend
        implements BluetoothAudioBackend {

    private static final String TAG = "HyperNovaBluetooth";

    /*
     * Hidden platform profile:
     * BluetoothProfile.A2DP_SINK = 11.
     */
    private static final int PROFILE_A2DP_SINK = 11;

    private static final String ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED";

    private static final ComponentName BLUETOOTH_MEDIA_BROWSER_COMPONENT =
            new ComponentName(
                    "com.android.bluetooth",
                    "com.android.bluetooth.avrcpcontroller."
                            + "BluetoothMediaBrowserService");

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService bluetoothExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread =
                        new Thread(runnable, "HyperNova-Bluetooth");
                thread.setDaemon(true);
                return thread;
            });

    private final AtomicBoolean refreshPending =
            new AtomicBoolean(false);

    private final List<Listener> listeners =
            new CopyOnWriteArrayList<>();

    private volatile BluetoothProfile a2dpSinkProfile;
    private volatile boolean a2dpSinkProxyRequested;

    private volatile String lastConnectedDeviceName = "";

    private volatile String remoteTitle = "";
    private volatile String remoteArtist = "";
    private volatile String remoteAlbum = "";
    private volatile boolean remotePlaying;
    private volatile long remoteDurationMs;
    private volatile long remotePositionMs;

    private MediaBrowser bluetoothMediaBrowser;
    private MediaController bluetoothMediaController;
    private boolean mediaBrowserConnectPending;

    private boolean receiverRegistered;

    private volatile BluetoothUiState state =
            new BluetoothUiState(
                    BluetoothUiState.Status.UNSUPPORTED,
                    "",
                    new ArrayList<>(),
                    "Bluetooth unavailable.");

    public PhoneBluetoothAudioBackend(Context context) {
        this.context = context.getApplicationContext();

        BluetoothManager manager =
                this.context.getSystemService(BluetoothManager.class);

        adapter = manager == null ? null : manager.getAdapter();

        refresh();
    }

    @Override
    public BluetoothUiState currentState() {
        return state;
    }

    @Override
    public void start(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }

        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();

            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(
                    BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            filter.addAction(
                    ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED);

            ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED);

            receiverRegistered = true;
        }

        ensureA2dpSinkProfile();
        ensureBluetoothMediaBrowser();
        refresh();
    }

    @Override
    public void stop(Listener listener) {
        listeners.remove(listener);

        if (!listeners.isEmpty()) {
            return;
        }

        if (receiverRegistered) {
            context.unregisterReceiver(receiver);
            receiverRegistered = false;
        }

        disconnectBluetoothMediaBrowser();
    }

    @Override
    public void refresh() {
        if (adapter == null) {
            update(
                    new BluetoothUiState(
                            BluetoothUiState.Status.UNSUPPORTED,
                            "",
                            new ArrayList<>(),
                            "This device does not expose Bluetooth hardware."));
            return;
        }

        if (!hasConnectPermission()) {
            update(
                    new BluetoothUiState(
                            BluetoothUiState.Status.PERMISSION_REQUIRED,
                            "",
                            new ArrayList<>(),
                            "Allow nearby-device access to show the connected phone."));
            return;
        }

        ensureA2dpSinkProfile();
        ensureBluetoothMediaBrowser();

        if (!refreshPending.compareAndSet(false, true)) {
            return;
        }

        bluetoothExecutor.execute(() -> {
            try {
                refreshBluetoothState();
            } finally {
                refreshPending.set(false);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void ensureA2dpSinkProfile() {
        if (adapter == null
                || !hasConnectPermission()
                || !adapter.isEnabled()
                || a2dpSinkProfile != null
                || a2dpSinkProxyRequested) {
            return;
        }

        a2dpSinkProxyRequested = true;

        boolean accepted;

        try {
            accepted =
                    adapter.getProfileProxy(
                            context,
                            profileServiceListener,
                            PROFILE_A2DP_SINK);
        } catch (RuntimeException error) {
            Log.w(
                    TAG,
                    "Unable to request A2DP Sink profile proxy",
                    error);
            accepted = false;
        }

        if (!accepted) {
            a2dpSinkProxyRequested = false;
        }
    }

    private void ensureBluetoothMediaBrowser() {
        mainHandler.post(() -> {
            if (listeners.isEmpty()
                    || bluetoothMediaBrowser != null
                    || mediaBrowserConnectPending) {
                return;
            }

            mediaBrowserConnectPending = true;

            Log.i(
                    TAG,
                    "Connecting to BluetoothMediaBrowserService");

            bluetoothMediaBrowser =
                    new MediaBrowser(
                            context,
                            BLUETOOTH_MEDIA_BROWSER_COMPONENT,
                            mediaBrowserConnectionCallback,
                            null);

            try {
                bluetoothMediaBrowser.connect();
            } catch (RuntimeException error) {
                Log.e(
                        TAG,
                        "Unable to connect Bluetooth MediaBrowser",
                        error);

                bluetoothMediaBrowser = null;
                mediaBrowserConnectPending = false;

                scheduleMediaBrowserReconnect();
            }
        });
    }

    private void disconnectBluetoothMediaBrowser() {
        mainHandler.post(() -> {
            mediaBrowserConnectPending = false;

            if (bluetoothMediaController != null) {
                try {
                    bluetoothMediaController.unregisterCallback(
                            mediaControllerCallback);
                } catch (RuntimeException error) {
                    Log.w(
                            TAG,
                            "Unable to unregister Bluetooth media callback",
                            error);
                }

                bluetoothMediaController = null;
            }

            if (bluetoothMediaBrowser != null) {
                try {
                    bluetoothMediaBrowser.disconnect();
                } catch (RuntimeException error) {
                    Log.w(
                            TAG,
                            "Unable to disconnect Bluetooth MediaBrowser",
                            error);
                }

                bluetoothMediaBrowser = null;
            }
        });
    }

    private void scheduleMediaBrowserReconnect() {
        mainHandler.removeCallbacks(mediaBrowserReconnectRunnable);

        if (!listeners.isEmpty()) {
            mainHandler.postDelayed(
                    mediaBrowserReconnectRunnable,
                    2_000L);
        }
    }

    private final Runnable mediaBrowserReconnectRunnable =
            this::ensureBluetoothMediaBrowser;

    /** Bluetooth Binder calls stay off the Activity input thread. */
    @SuppressLint("MissingPermission")
    private void refreshBluetoothState() {
        try {
            if (!adapter.isEnabled()) {
                update(
                        new BluetoothUiState(
                                BluetoothUiState.Status.OFF,
                                "",
                                new ArrayList<>(),
                                "Bluetooth is turned off."));
                return;
            }

            List<String> pairedDevices = pairedDeviceNames();
            List<BluetoothDevice> connectedDevices =
                    connectedA2dpSinkDevices();

            if (!connectedDevices.isEmpty()) {
                BluetoothDevice activeDevice =
                        connectedDevices.get(0);

                String activeName =
                        safeDeviceName(activeDevice);

                lastConnectedDeviceName = activeName;

                if (!pairedDevices.contains(activeName)) {
                    pairedDevices.add(0, activeName);
                }

                update(
                        new BluetoothUiState(
                                BluetoothUiState.Status.CONNECTED,
                                activeName,
                                pairedDevices,
                                connectedBluetoothDetail(),
                                remoteTitle,
                                remoteArtist,
                                remoteAlbum,
                                remotePlaying,
                                remotePositionMs,
                                remoteDurationMs));
                return;
            }

            int profileState =
                    adapter.getProfileConnectionState(
                            PROFILE_A2DP_SINK);

            if (profileState == BluetoothProfile.STATE_CONNECTING) {
                update(
                        new BluetoothUiState(
                                BluetoothUiState.Status.CONNECTING,
                                lastConnectedDeviceName,
                                pairedDevices,
                                "Connecting the phone audio profile."));
                return;
            }

            /*
             * The profile proxy is asynchronous. The adapter may report the
             * A2DP Sink profile as connected before getConnectedDevices()
             * becomes available.
             */
            if (profileState == BluetoothProfile.STATE_CONNECTED) {
                String fallbackName =
                        lastConnectedDeviceName;

                if (fallbackName.isEmpty()
                        && pairedDevices.size() == 1) {
                    fallbackName = pairedDevices.get(0);
                }

                if (fallbackName.isEmpty()) {
                    fallbackName = "Connected phone";
                }

                update(
                        new BluetoothUiState(
                                BluetoothUiState.Status.CONNECTED,
                                fallbackName,
                                pairedDevices,
                                connectedBluetoothDetail(),
                                remoteTitle,
                                remoteArtist,
                                remoteAlbum,
                                remotePlaying,
                                remotePositionMs,
                                remoteDurationMs));
                return;
            }

            if (pairedDevices.isEmpty()) {
                update(
                        new BluetoothUiState(
                                BluetoothUiState.Status.ON_NO_PAIRED_AUDIO,
                                "",
                                pairedDevices,
                                "Bluetooth is on. No paired phones were found."));
                return;
            }

            update(
                    new BluetoothUiState(
                            BluetoothUiState.Status.PAIRED,
                            "",
                            pairedDevices,
                            "Paired phone available; media audio is not connected."));
        } catch (SecurityException error) {
            Log.w(
                    TAG,
                    "Bluetooth permission changed during refresh",
                    error);

            update(
                    new BluetoothUiState(
                            BluetoothUiState.Status.PERMISSION_REQUIRED,
                            "",
                            new ArrayList<>(),
                            "Nearby-device permission is required."));
        } catch (RuntimeException error) {
            Log.e(
                    TAG,
                    "Bluetooth state refresh failed",
                    error);

            update(
                    new BluetoothUiState(
                            BluetoothUiState.Status.ERROR,
                            "",
                            new ArrayList<>(),
                            "Unable to read the Bluetooth audio state."));
        }
    }

    private String connectedBluetoothDetail() {
        String title = remoteTitle;
        String artist = remoteArtist;
        String album = remoteAlbum;

        if (title.isEmpty()) {
            return "Connected for Bluetooth media audio.";
        }

        StringBuilder value =
                new StringBuilder(title);

        if (!artist.isEmpty()) {
            value.append(" — ").append(artist);
        } else if (!album.isEmpty()) {
            value.append(" — ").append(album);
        }

        value.append(remotePlaying ? " • Playing" : " • Paused");

        return value.toString();
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * A phone normally reports PHONE as its major Bluetooth class even while
     * it provides A2DP, AVRCP, HFP and PBAP.
     */
    @SuppressLint("MissingPermission")
    private List<String> pairedDeviceNames() {
        List<String> result = new ArrayList<>();

        if (!hasConnectPermission()) {
            return result;
        }

        Set<String> uniqueNames =
                new LinkedHashSet<>();

        for (BluetoothDevice device :
                adapter.getBondedDevices()) {
            uniqueNames.add(safeDeviceName(device));
        }

        result.addAll(uniqueNames);
        return result;
    }

    @SuppressLint("MissingPermission")
    private List<BluetoothDevice> connectedA2dpSinkDevices() {
        BluetoothProfile profile = a2dpSinkProfile;

        if (profile == null || !hasConnectPermission()) {
            return new ArrayList<>();
        }

        try {
            return new ArrayList<>(
                    profile.getConnectedDevices());
        } catch (RuntimeException error) {
            Log.w(
                    TAG,
                    "Unable to read A2DP Sink devices",
                    error);

            return new ArrayList<>();
        }
    }

    @SuppressLint("MissingPermission")
    private String safeDeviceName(BluetoothDevice device) {
        if (device == null || !hasConnectPermission()) {
            return "Bluetooth device";
        }

        String name = device.getName();

        if (name == null || name.trim().isEmpty()) {
            return "Unnamed Bluetooth device";
        }

        return name.trim();
    }

    private void readBluetoothMediaSession() {
        MediaController controller =
                bluetoothMediaController;

        if (controller == null) {
            clearRemoteMedia();
            return;
        }

        updateRemoteMetadata(controller.getMetadata());
        updateRemotePlayback(controller.getPlaybackState());
    }

    private void updateRemoteMetadata(MediaMetadata metadata) {
        if (metadata == null) {
            remoteTitle = "";
            remoteArtist = "";
            remoteAlbum = "";
            remoteDurationMs = 0L;
            return;
        }

        remoteTitle =
                cleanMetadataText(
                        metadata.getString(
                                MediaMetadata.METADATA_KEY_TITLE));

        remoteArtist =
                cleanMetadataText(
                        metadata.getString(
                                MediaMetadata.METADATA_KEY_ARTIST));

        remoteAlbum =
                cleanMetadataText(
                        metadata.getString(
                                MediaMetadata.METADATA_KEY_ALBUM));

        remoteDurationMs =
                Math.max(
                        0L,
                        metadata.getLong(
                                MediaMetadata.METADATA_KEY_DURATION));

        Log.i(
                TAG,
                "Remote metadata: title="
                        + remoteTitle
                        + ", artist="
                        + remoteArtist
                        + ", album="
                        + remoteAlbum);
    }

    private void updateRemotePlayback(PlaybackState playbackState) {
        if (playbackState == null) {
            remotePlaying = false;
            remotePositionMs = 0L;
            return;
        }

        int value = playbackState.getState();

        remotePlaying =
                value == PlaybackState.STATE_PLAYING
                        || value == PlaybackState.STATE_BUFFERING
                        || value == PlaybackState.STATE_CONNECTING;

        remotePositionMs =
                Math.max(0L, playbackState.getPosition());

        Log.i(
                TAG,
                "Remote playback state="
                        + value
                        + ", position="
                        + remotePositionMs
                        + ", duration="
                        + remoteDurationMs);
    }

    private String cleanMetadataText(String value) {
        if (value == null) {
            return "";
        }

        String cleaned = value.trim();

        if (cleaned.isEmpty()
                || "Not Provided".equalsIgnoreCase(cleaned)
                || "Unknown".equalsIgnoreCase(cleaned)) {
            return "";
        }

        return cleaned;
    }

    private void clearRemoteMedia() {
        remoteTitle = "";
        remoteArtist = "";
        remoteAlbum = "";
        remotePlaying = false;
        remoteDurationMs = 0L;
        remotePositionMs = 0L;
    }

    /*
     * These methods will be connected to MainActivity transport buttons in
     * the next step.
     */
    public void playPauseRemote() {
        mainHandler.post(() -> {
            MediaController controller =
                    bluetoothMediaController;

            if (controller == null) {
                return;
            }

            if (remotePlaying) {
                controller.getTransportControls().pause();
            } else {
                controller.getTransportControls().play();
            }
        });
    }

    public void previousRemote() {
        mainHandler.post(() -> {
            if (bluetoothMediaController != null) {
                bluetoothMediaController
                        .getTransportControls()
                        .skipToPrevious();
            }
        });
    }

    public void nextRemote() {
        mainHandler.post(() -> {
            if (bluetoothMediaController != null) {
                bluetoothMediaController
                        .getTransportControls()
                        .skipToNext();
            }
        });
    }

    public void seekRemoteTo(long positionMs) {
        mainHandler.post(() -> {
            MediaController controller =
                    bluetoothMediaController;

            if (controller == null) {
                return;
            }

            long target = Math.max(0L, positionMs);

            if (remoteDurationMs > 0L) {
                target = Math.min(target, remoteDurationMs);
            }

            controller.getTransportControls().seekTo(target);
        });
    }

    public void seekRemoteBy(long offsetMs) {
        seekRemoteTo(remotePositionMs + offsetMs);
    }

    private void update(BluetoothUiState value) {
        state = value;

        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onBluetoothStateChanged(value);
            }
        });
    }

    private final BluetoothProfile.ServiceListener
            profileServiceListener =
            new BluetoothProfile.ServiceListener() {

                @Override
                public void onServiceConnected(
                        int profile,
                        BluetoothProfile proxy) {
                    if (profile != PROFILE_A2DP_SINK) {
                        return;
                    }

                    Log.i(
                            TAG,
                            "A2DP Sink profile proxy connected");

                    a2dpSinkProfile = proxy;
                    a2dpSinkProxyRequested = false;

                    ensureBluetoothMediaBrowser();
                    refresh();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile != PROFILE_A2DP_SINK) {
                        return;
                    }

                    Log.w(
                            TAG,
                            "A2DP Sink profile proxy disconnected");

                    a2dpSinkProfile = null;
                    a2dpSinkProxyRequested = false;

                    refresh();
                }
            };

    private final MediaBrowser.ConnectionCallback
            mediaBrowserConnectionCallback =
            new MediaBrowser.ConnectionCallback() {

                @Override
                public void onConnected() {
                    mediaBrowserConnectPending = false;

                    MediaBrowser browser =
                            bluetoothMediaBrowser;

                    if (browser == null || !browser.isConnected()) {
                        scheduleMediaBrowserReconnect();
                        return;
                    }

                    Log.i(
                            TAG,
                            "BluetoothMediaBrowserService connected");

                    if (bluetoothMediaController != null) {
                        bluetoothMediaController.unregisterCallback(
                                mediaControllerCallback);
                    }

                    bluetoothMediaController =
                            new MediaController(
                                    context,
                                    browser.getSessionToken());

                    bluetoothMediaController.registerCallback(
                            mediaControllerCallback,
                            mainHandler);

                    readBluetoothMediaSession();
                    refresh();
                }

                @Override
                public void onConnectionSuspended() {
                    Log.w(
                            TAG,
                            "Bluetooth MediaBrowser connection suspended");

                    mediaBrowserConnectPending = false;
                    bluetoothMediaController = null;
                    bluetoothMediaBrowser = null;

                    clearRemoteMedia();
                    refresh();
                    scheduleMediaBrowserReconnect();
                }

                @Override
                public void onConnectionFailed() {
                    Log.e(
                            TAG,
                            "Bluetooth MediaBrowser connection failed");

                    mediaBrowserConnectPending = false;
                    bluetoothMediaController = null;
                    bluetoothMediaBrowser = null;

                    clearRemoteMedia();
                    refresh();
                    scheduleMediaBrowserReconnect();
                }
            };

    private final MediaController.Callback mediaControllerCallback =
            new MediaController.Callback() {

                @Override
                public void onMetadataChanged(
                        MediaMetadata metadata) {
                    updateRemoteMetadata(metadata);
                    refresh();
                }

                @Override
                public void onPlaybackStateChanged(
                        PlaybackState playbackState) {
                    updateRemotePlayback(playbackState);
                    refresh();
                }

                @Override
                public void onSessionDestroyed() {
                    Log.w(
                            TAG,
                            "Bluetooth media session destroyed");

                    bluetoothMediaController = null;
                    clearRemoteMedia();

                    refresh();
                    scheduleMediaBrowserReconnect();
                }
            };

    private final BroadcastReceiver receiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(
                        Context receiverContext,
                        Intent intent) {
                    String action = intent.getAction();

                    if (action == null) {
                        refresh();
                        return;
                    }

                    BluetoothDevice device =
                            getBluetoothDeviceExtra(intent);

                    if (device != null
                            && (BluetoothDevice.ACTION_ACL_CONNECTED.equals(
                                            action)
                                    || ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED
                                            .equals(action))) {

                        int connectionState =
                                intent.getIntExtra(
                                        BluetoothProfile.EXTRA_STATE,
                                        BluetoothProfile.STATE_DISCONNECTED);

                        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(
                                        action)
                                || connectionState
                                        == BluetoothProfile.STATE_CONNECTED) {
                            lastConnectedDeviceName =
                                    safeDeviceName(device);

                            ensureBluetoothMediaBrowser();
                        }
                    }

                    if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(
                            action)) {
                        int adapterState =
                                intent.getIntExtra(
                                        BluetoothAdapter.EXTRA_STATE,
                                        BluetoothAdapter.ERROR);

                        if (adapterState
                                == BluetoothAdapter.STATE_ON) {
                            ensureA2dpSinkProfile();
                            ensureBluetoothMediaBrowser();
                        } else if (adapterState
                                == BluetoothAdapter.STATE_OFF) {
                            a2dpSinkProfile = null;
                            a2dpSinkProxyRequested = false;
                            lastConnectedDeviceName = "";

                            clearRemoteMedia();
                            disconnectBluetoothMediaBrowser();
                        }
                    }

                    refresh();
                }
            };

    @SuppressWarnings("deprecation")
    private BluetoothDevice getBluetoothDeviceExtra(
            Intent intent) {
        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice.class);
        }

        return intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE);
    }
}
