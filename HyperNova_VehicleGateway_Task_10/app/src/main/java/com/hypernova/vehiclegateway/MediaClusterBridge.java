package com.hypernova.vehiclegateway;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.hypernova.contracts.media.IMediaStatusCallback;
import com.hypernova.contracts.media.IMediaStatusService;
import com.hypernova.contracts.media.MediaContract;
import com.hypernova.contracts.media.MediaPlaybackSnapshot;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges HyperNova Media's read-only AIDL playback status stream
 * into the dedicated HNMC Digital Cluster media transport.
 *
 * Separation mirrors NavigationClusterBridge:
 *
 *   Media AIDL status --(binder callbacks)--> latest-state swap
 *   ClusterMediaConnection performs all socket I/O off the callback thread
 *
 * No TCP I/O happens on Binder callback threads: publishing or clearing
 * media only swaps atomically retained presentation frames, so a slow or
 * busy cluster socket can never stall the Media process.
 */
final class MediaClusterBridge {

    private static final String TAG = "HN-MediaClusterBridge";

    private static final long REBIND_DELAY_MILLIS = 1_000L;

    /**
     * HNMC caps each media text field at 2048 UTF-8 bytes.
     *
     * 512 Java chars can never exceed that budget (worst case is 1536
     * bytes with 3-byte BMP characters), so media id, title, artist, and
     * album are trimmed to this length before publication.
     */
    private static final int MAX_TEXT_CHARS = 512;

    private final Context context;
    private final ClusterMediaConnection clusterConnection;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile boolean bound;
    private boolean rebindScheduled;
    private volatile IMediaStatusService mediaService;

    MediaClusterBridge(
            Context context,
            ClusterMediaConnection clusterConnection
    ) {
        this.context = context;
        this.clusterConnection = clusterConnection;
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        bindMediaService();
    }

    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        IMediaStatusService service = mediaService;
        mediaService = null;

        if (service != null) {
            try {
                service.unregisterMediaStatusCallback(statusCallback);
            } catch (RemoteException ignored) {
                // Media process may already be gone.
            }
        }

        if (bound) {
            try {
                context.unbindService(serviceConnection);
            } catch (IllegalArgumentException ignored) {
                // Already unbound by the framework.
            }
        }

        bound = false;

        /*
         * Cancels any pending controlled rebind so stop() leaves no
         * scheduled retries behind and stays idempotent.
         */
        mainHandler.removeCallbacksAndMessages(null);
        rebindScheduled = false;

        Log.i(TAG, "Media cluster bridge stopped");
    }

    private void bindMediaService() {
        if (!running.get() || bound) {
            return;
        }

        Intent intent = new Intent(MediaContract.BIND_STATUS_ACTION);
        intent.setComponent(
                new ComponentName(
                        MediaContract.PACKAGE_NAME,
                        MediaContract.STATUS_SERVICE
                )
        );

        try {
            bound = context.bindService(
                    intent,
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
            );

            if (bound) {
                Log.i(
                        TAG,
                        "Bind requested for HyperNova Media status service"
                );
            } else {
                Log.w(TAG, "Media status service bind request was rejected");
                scheduleRebind();
            }
        } catch (RuntimeException error) {
            bound = false;
            Log.w(
                    TAG,
                    "Unable to bind Media status service: "
                            + error.getMessage()
            );
            scheduleRebind();
        }
    }

    /**
     * Schedules exactly one controlled rebind attempt.
     *
     * rebindScheduled collapses bursts (disconnect + binding died + rejected
     * bind) into a single pending retry; the flag is cleared only when the
     * retry actually runs or when stop() cancels it.
     */
    private void scheduleRebind() {
        if (!running.get() || rebindScheduled) {
            return;
        }

        rebindScheduled = true;

        Log.i(
                TAG,
                "Scheduling Media status rebind in "
                        + REBIND_DELAY_MILLIS
                        + " ms"
        );

        mainHandler.postDelayed(
                () -> {
                    rebindScheduled = false;
                    bindMediaService();
                },
                REBIND_DELAY_MILLIS
        );
    }

    private final ServiceConnection serviceConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(
                        ComponentName name,
                        IBinder binder
                ) {
                    if (!running.get()) {
                        return;
                    }

                    IMediaStatusService service =
                            IMediaStatusService.Stub.asInterface(binder);

                    try {
                        int apiVersion = service.getApiVersion();

                        if (apiVersion != MediaContract.API_VERSION) {
                            Log.e(
                                    TAG,
                                    "Media API mismatch: "
                                            + apiVersion
                                            + " (expected "
                                            + MediaContract.API_VERSION
                                            + ")"
                            );
                            return;
                        }

                        service.registerMediaStatusCallback(statusCallback);

                        mediaService = service;

                        /*
                         * Registration replays the current snapshot, but an
                         * explicit fetch closes any race where playback
                         * changed between the replay and registration.
                         */
                        applySnapshot(
                                clusterConnection,
                                service.getCurrentSnapshot());

                        Log.i(
                                TAG,
                                "Subscribed to HyperNova Media playback status"
                        );
                    } catch (RemoteException error) {
                        mediaService = null;
                        Log.w(
                                TAG,
                                "Media subscription failed: "
                                        + error.getMessage()
                        );
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    mediaService = null;
                    Log.w(TAG, "Media status service disconnected");

                    /*
                     * The framework keeps the binding alive and usually
                     * reconnects on its own; a controlled retry is skipped
                     * while still bound and recovers the rare case where
                     * automatic reconnection never completes.
                     */
                    scheduleRebind();
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    mediaService = null;
                    Log.w(TAG, "Media status service binding died");

                    if (bound) {
                        try {
                            context.unbindService(serviceConnection);
                        } catch (IllegalArgumentException ignored) {
                            // The system already detached this binding.
                        }
                        bound = false;
                    }

                    scheduleRebind();
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    mediaService = null;
                    Log.e(TAG, "Media status service returned null binding");

                    if (bound) {
                        try {
                            context.unbindService(serviceConnection);
                        } catch (IllegalArgumentException ignored) {
                            // Nothing left to release.
                        }
                        bound = false;
                    }

                    scheduleRebind();
                }
            };

    private final IMediaStatusCallback statusCallback =
            new IMediaStatusCallback.Stub() {
                @Override
                public void onMediaPlaybackSnapshot(
                        MediaPlaybackSnapshot snapshot
                ) {
                    if (!running.get()) {
                        return;
                    }

                    /*
                     * Binder thread: applySnapshot only performs atomic
                     * latest-state updates on ClusterMediaConnection and
                     * never blocks on TCP I/O.
                     */
                    applySnapshot(clusterConnection, snapshot);
                }
            };

    /**
     * Maps one Media playback snapshot onto the HNMC presentation state.
     *
     * hasMedia=false becomes an explicit MEDIA_CLEAR. Active media becomes
     * a MEDIA_STATE with clamped position/duration and trimmed text fields.
     * artworkUri is intentionally never forwarded to HNMC.
     */
    static void applySnapshot(
            ClusterMediaConnection connection,
            MediaPlaybackSnapshot snapshot
    ) {
        if (connection == null || snapshot == null) {
            return;
        }

        if (!snapshot.hasMedia()) {
            connection.clearMedia();
            return;
        }

        connection.publishMediaState(
                true,
                snapshot.isPlaying(),
                clampNonNegative(snapshot.getPositionMs()),
                clampNonNegative(snapshot.getDurationMs()),
                limitText(snapshot.getMediaId()),
                limitText(snapshot.getTitle()),
                limitText(snapshot.getArtist()),
                limitText(snapshot.getAlbum())
        );
    }

    static long clampNonNegative(long value) {
        return Math.max(0L, value);
    }

    private static String limitText(String value) {
        if (value == null) {
            return "";
        }

        return value.length() <= MAX_TEXT_CHARS
                ? value
                : value.substring(0, MAX_TEXT_CHARS);
    }
}
