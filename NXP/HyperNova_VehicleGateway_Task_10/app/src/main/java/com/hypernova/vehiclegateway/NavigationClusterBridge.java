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

import com.hypernova.contracts.HyperNovaContract;
import com.hypernova.contracts.navigation.INavigationCommandService;
import com.hypernova.contracts.navigation.INavigationStatusCallback;
import com.hypernova.contracts.navigation.NavigationContract;
import com.hypernova.contracts.navigation.NavigationCurrentPosition;
import com.hypernova.contracts.navigation.NavigationDestination;
import com.hypernova.contracts.navigation.NavigationProgressSnapshot;
import com.hypernova.contracts.navigation.NavigationRouteSnapshot;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges HyperNova Navigation's existing read-only AIDL status stream
 * into the dedicated HNCL Digital Cluster transport.
 *
 * No socket I/O happens on Binder callback threads.
 *
 * The current Navigation contract exposes:
 *   - navigation state
 *   - destination
 *   - total route distance/duration
 *   - live current position
 *   - live bearing/speed
 *   - live remaining distance
 *
 * Maneuver/street/speed-limit guidance is intentionally left unknown until
 * Navigation exposes authoritative current-guidance data through its contract.
 */
final class NavigationClusterBridge {

    private static final String TAG = "HN-NavClusterBridge";

    /**
     * UINT32_MAX is the HNCL sentinel used for currently unavailable
     * distance/time presentation values.
     */
    private static final long UNKNOWN_UINT32 = 0xFFFF_FFFFL;

    private final Context context;
    private final ClusterNavigationConnection clusterConnection;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile boolean bound;
    private boolean rebindScheduled;
    private volatile INavigationCommandService navigationService;

    private volatile NavigationRouteSnapshot latestRoute;
    private volatile NavigationProgressSnapshot latestProgress;

    NavigationClusterBridge(
            Context context,
            ClusterNavigationConnection clusterConnection
    ) {
        this.context = context;
        this.clusterConnection = clusterConnection;
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        bindNavigationService();
    }

    private void bindNavigationService() {
        if (!running.get() || bound) {
            return;
        }

        Intent intent = new Intent(NavigationContract.BIND_COMMAND_ACTION);
        intent.setComponent(
                new ComponentName(
                        NavigationContract.PACKAGE_NAME,
                        NavigationContract.COMMAND_SERVICE
                )
        );

        try {
            bound = context.bindService(
                    intent,
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
            );

            if (!bound) {
                Log.w(TAG, "Navigation service bind request was rejected");
            }
        } catch (RuntimeException error) {
            bound = false;
            Log.w(
                    TAG,
                    "Unable to bind Navigation service: "
                            + error.getMessage()
            );
        }
    }

    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        INavigationCommandService service = navigationService;
        navigationService = null;

        if (service != null) {
            try {
                service.unregisterNavigationStatusCallback(statusCallback);
            } catch (RemoteException ignored) {
                // Navigation process may already be gone.
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
        mainHandler.removeCallbacksAndMessages(null);
        rebindScheduled = false;
        latestRoute = null;
        latestProgress = null;
    }

    private void scheduleRebind() {
        if (!running.get() || rebindScheduled) {
            return;
        }

        rebindScheduled = true;
        mainHandler.postDelayed(
                () -> {
                    rebindScheduled = false;
                    bindNavigationService();
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

                    INavigationCommandService service =
                            INavigationCommandService.Stub.asInterface(binder);

                    try {
                        int apiVersion = service.getApiVersion();

                        if (apiVersion != HyperNovaContract.API_VERSION) {
                            Log.e(
                                    TAG,
                                    "Navigation API mismatch: "
                                            + apiVersion
                            );
                            return;
                        }

                        navigationService = service;
                        service.registerNavigationStatusCallback(
                                statusCallback
                        );

                        Log.i(
                                TAG,
                                "Subscribed to Navigation status"
                        );
                    } catch (RemoteException error) {
                        navigationService = null;
                        Log.w(
                                TAG,
                                "Navigation subscription failed: "
                                        + error.getMessage()
                        );
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    navigationService = null;
                    Log.w(TAG, "Navigation service disconnected");
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    navigationService = null;
                    Log.w(TAG, "Navigation service binding died");
                    if (bound) {
                        try {
                            context.unbindService(serviceConnection);
                        } catch (IllegalArgumentException ignored) {
                            // The system has already detached this binding.
                        }
                        bound = false;
                    }
                    scheduleRebind();
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    navigationService = null;
                    Log.e(TAG, "Navigation service returned null binding");
                }
            };

    private final INavigationStatusCallback statusCallback =
            new INavigationStatusCallback.Stub() {
                @Override
                public void onRouteSnapshot(
                        NavigationRouteSnapshot snapshot
                ) {
                    if (!running.get() || snapshot == null) {
                        return;
                    }

                    latestRoute = snapshot;

                    /*
                     * Route callback normally arrives before the matching
                     * progress callback. Do not mix a new route with stale
                     * position data from the previous route.
                     */
                    NavigationProgressSnapshot progress = latestProgress;

                    if (!matches(snapshot, progress)) {
                        clusterConnection.clearNavigation();
                        return;
                    }

                    publish(snapshot, progress);
                }

                @Override
                public void onProgressSnapshot(
                        NavigationProgressSnapshot snapshot
                ) {
                    if (!running.get() || snapshot == null) {
                        return;
                    }

                    latestProgress = snapshot;

                    NavigationRouteSnapshot route = latestRoute;

                    if (!matches(route, snapshot)) {
                        return;
                    }

                    publish(route, snapshot);
                }
            };

    private void publish(
            NavigationRouteSnapshot route,
            NavigationProgressSnapshot progress
    ) {
        int state = progress.getNavigationState();

        if (state == NavigationContract.STATE_IDLE
                || state == NavigationContract.STATE_CALCULATING
                || state == NavigationContract.STATE_ERROR) {
            clusterConnection.clearNavigation();
            return;
        }

        NavigationCurrentPosition position =
                progress.getCurrentPosition();

        /*
         * During ACTIVE navigation we only publish when Navigation has an
         * authoritative position. Registration sends route followed by
         * progress, so this should normally be available immediately.
         */
        if (state == NavigationContract.STATE_ACTIVE
                && !valid(position)) {
            return;
        }

        long remainingDistance =
                toUnsigned32OrUnknown(
                        progress.getRemainingDistanceMeters()
                );

        long remainingTime =
                estimateRemainingTimeSeconds(
                        route,
                        remainingDistance
                );

        long etaEpochSeconds =
                remainingTime == UNKNOWN_UINT32
                        ? 0L
                        : (System.currentTimeMillis() / 1000L)
                                + remainingTime;

        NavigationDestination selectedDestination =
                route.getSelectedDestination();

        String destination =
                selectedDestination == null
                        ? ""
                        : safe(selectedDestination.getTitle());

        boolean arrived =
                state == NavigationContract.STATE_ARRIVED;

        double latitude =
                valid(position)
                        ? position.getLatitude()
                        : 0.0;

        double longitude =
                valid(position)
                        ? position.getLongitude()
                        : 0.0;

        float heading =
                valid(position)
                        ? position.getBearingDegrees()
                        : 0.0f;

        clusterConnection.publishNavigationState(
                state == NavigationContract.STATE_ACTIVE,
                arrived
                        ? ClusterNavigationProtocol.MANEUVER_ARRIVE
                        : ClusterNavigationProtocol.MANEUVER_UNKNOWN,

                // No authoritative speed-limit source in current contract.
                -1,

                // Current AIDL does not expose distance to next maneuver.
                UNKNOWN_UINT32,

                remainingDistance,
                remainingTime,
                etaEpochSeconds,
                latitude,
                longitude,
                heading,

                // Current AIDL does not expose the active road name.
                "",

                destination
        );
    }

    private static final long REBIND_DELAY_MILLIS = 1_000L;

    private boolean matches(
            NavigationRouteSnapshot route,
            NavigationProgressSnapshot progress
    ) {
        if (route == null || progress == null) {
            return false;
        }

        String routeId = route.getRouteId();

        return routeId != null
                && !routeId.isBlank()
                && routeId.equals(progress.getRouteId())
                && route.getRouteVersion()
                        == progress.getRouteVersion();
    }

    private long estimateRemainingTimeSeconds(
            NavigationRouteSnapshot route,
            long remainingDistance
    ) {
        if (remainingDistance == UNKNOWN_UINT32) {
            return UNKNOWN_UINT32;
        }

        long totalDistance = route.getDistanceMeters();
        long totalDuration = route.getEtaSeconds();

        if (totalDistance <= 0
                || totalDuration < 0
                || remainingDistance > totalDistance) {
            return UNKNOWN_UINT32;
        }

        double fraction =
                (double) remainingDistance
                        / (double) totalDistance;

        double estimate =
                totalDuration * fraction;

        if (!Double.isFinite(estimate)
                || estimate < 0.0
                || estimate > UNKNOWN_UINT32 - 1L) {
            return UNKNOWN_UINT32;
        }

        return Math.round(estimate);
    }

    private long toUnsigned32OrUnknown(long value) {
        if (value < 0 || value >= UNKNOWN_UINT32) {
            return UNKNOWN_UINT32;
        }

        return value;
    }

    private boolean valid(NavigationCurrentPosition position) {
        if (position == null) {
            return false;
        }

        double latitude = position.getLatitude();
        double longitude = position.getLongitude();

        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90.0
                && latitude <= 90.0
                && longitude >= -180.0
                && longitude <= 180.0;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
