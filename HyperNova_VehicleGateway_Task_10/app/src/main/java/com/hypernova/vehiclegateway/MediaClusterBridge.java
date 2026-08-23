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
 *
 * Every form of connection loss (service disconnected, binding died, null
 * binding, rejected bind) releases the stale binding immediately and then
 * schedules exactly one controlled rebind attempt. stop() cancels that
 * retry, releases the binding, and stays idempotent.
 */
class MediaClusterBridge {

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

    /**
     * Created lazily so plain-JVM tests can drive the bridge through the
     * overridden framework hooks without touching android.os.Handler.
     */
    private Handler mainHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile boolean bound;
    private boolean rebindScheduled;
    private volatile IMediaStatusService mediaService;

    /**
     * Created lazily because constructing an AIDL Stub instantiates a
     * native-backed Binder, which plain-JVM tests must not trigger.
     */
    private IMediaStatusCallback statusCallbackInstance;

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
                service.unregisterMediaStatusCallback(statusCallback());
            } catch (RemoteException ignored) {
                // Media process may already be gone.
            }
        }

        releaseStaleBinding();

        /*
         * Cancels any pending controlled rebind so stop() leaves no
         * scheduled retries behind and stays idempotent.
         */
        cancelScheduledWork();
        rebindScheduled = false;

        logInfo("Media cluster bridge stopped");
    }

    private void bindMediaService() {
        if (!running.get() || bound) {
            return;
        }

        try {
            bound = performBind();

            if (bound) {
                logInfo("Bind requested for HyperNova Media status service");
            } else {
                logWarning("Media status service bind request was rejected");
                scheduleControlledRebind();
            }
        } catch (RuntimeException error) {
            bound = false;
            logWarning(
                    "Unable to bind Media status service: "
                            + error.getMessage()
            );
            scheduleControlledRebind();
        }
    }

    /**
     * Releases a still-held binding exactly once.
     *
     * Used on connection loss and during stop() so a stale binding can
     * never block the controlled rebind.
     */
    private void releaseStaleBinding() {
        if (!bound) {
            return;
        }

        performUnbind();
        bound = false;
    }

    /**
     * Shared recovery path for disconnect, binding death, and null binding:
     * drop the service reference, release the stale binding, then schedule
     * exactly one controlled rebind.
     */
    private void handleServiceLost(String message) {
        mediaService = null;
        logWarning(message);
        releaseStaleBinding();
        scheduleControlledRebind();
    }

    /**
     * Schedules exactly one controlled rebind attempt.
     *
     * rebindScheduled collapses bursts (disconnect + binding died + rejected
     * bind) into a single pending retry; the flag is cleared only when the
     * retry actually runs or when stop() cancels it.
     */
    private void scheduleControlledRebind() {
        if (!running.get() || rebindScheduled) {
            return;
        }

        rebindScheduled = true;

        logInfo(
                "Scheduling Media status rebind in "
                        + REBIND_DELAY_MILLIS
                        + " ms"
        );

        scheduleRetry(
                () -> {
                    rebindScheduled = false;
                    bindMediaService();
                }
        );
    }

    final ServiceConnection serviceConnection =
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
                            logError(
                                    "Media API mismatch: "
                                            + apiVersion
                                            + " (expected "
                                            + MediaContract.API_VERSION
                                            + ")"
                            );
                            return;
                        }

                        service.registerMediaStatusCallback(statusCallback());

                        mediaService = service;

                        /*
                         * Registration replays the current snapshot, but an
                         * explicit fetch closes any race where playback
                         * changed between the replay and registration.
                         */
                        applySnapshot(
                                clusterConnection,
                                service.getCurrentSnapshot());

                        logInfo(
                                "Subscribed to HyperNova Media playback status"
                        );
                    } catch (RemoteException error) {

                        /*
                         * The binding itself stays alive, so the framework
                         * redelivers onServiceConnected when Media returns;
                         * releasing the binding here could fight that
                         * automatic reconnection.
                         */
                        mediaService = null;
                        logWarning(
                                "Media subscription failed: "
                                        + error.getMessage()
                        );
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    handleServiceLost("Media status service disconnected");
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    handleServiceLost("Media status service binding died");
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    handleServiceLost(
                            "Media status service returned null binding");
                }
            };

    private IMediaStatusCallback statusCallback() {
        if (statusCallbackInstance == null) {
            statusCallbackInstance =
                    new IMediaStatusCallback.Stub() {
                        @Override
                        public void onMediaPlaybackSnapshot(
                                MediaPlaybackSnapshot snapshot
                        ) {
                            if (!running.get()) {
                                return;
                            }

                            /*
                             * Binder thread: applySnapshot only performs
                             * atomic latest-state updates on
                             * ClusterMediaConnection and never blocks on
                             * TCP I/O.
                             */
                            applySnapshot(clusterConnection, snapshot);
                        }
                    };
        }

        return statusCallbackInstance;
    }

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

    /*
     * Framework hooks.
     *
     * The defaults wrap the real Android calls; focused plain-JVM tests
     * override them to observe binding and retry behavior without an
     * Android runtime. Production behavior is identical either way.
     */

    /**
     * Explicitly binds the HyperNova Media status service by action and
     * ComponentName. Returns false when the framework rejects the request.
     */
    boolean performBind() {
        Intent intent = new Intent(MediaContract.BIND_STATUS_ACTION);
        intent.setComponent(
                new ComponentName(
                        MediaContract.PACKAGE_NAME,
                        MediaContract.STATUS_SERVICE
                )
        );

        return context.bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
        );
    }

    void performUnbind() {
        try {
            context.unbindService(serviceConnection);
        } catch (IllegalArgumentException ignored) {
            // Already unbound by the framework.
        }
    }

    void scheduleRetry(Runnable retry) {
        mainHandler().postDelayed(retry, REBIND_DELAY_MILLIS);
    }

    void cancelScheduledWork() {
        mainHandler().removeCallbacksAndMessages(null);
    }

    private Handler mainHandler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }

        return mainHandler;
    }

    void logInfo(String message) {
        Log.i(TAG, message);
    }

    void logWarning(String message) {
        Log.w(TAG, message);
    }

    void logError(String message) {
        Log.e(TAG, message);
    }
}
