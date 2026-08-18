package com.hypernova.vehiclegateway;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.hypernova.contracts.HyperNovaContract;
import com.hypernova.contracts.vehiclegateway.IVehicleGatewayCallback;
import com.hypernova.contracts.vehiclegateway.IVehicleGatewayService;
import com.hypernova.contracts.vehiclegateway.IVehicleStateListener;
import com.hypernova.contracts.vehiclegateway.VehicleClimateCommand;
import com.hypernova.contracts.vehiclegateway.VehicleFaultEvent;
import com.hypernova.contracts.vehiclegateway.VehicleGatewayContract;
import com.hypernova.contracts.vehiclegateway.VehicleGatewayResult;
import com.hypernova.contracts.vehiclegateway.VehicleState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Headless, signature-protected Android broker for QNX integration.
 *
 * Two independent transports are owned here:
 *
 *   1. GatewayConnection
 *      HNVG / TCP 6100
 *      Vehicle + Climate + TC397 path.
 *
 *   2. ClusterNavigationConnection
 *      HNCL / TCP 6200
 *      Navigation presentation -> QNX Digital Cluster.
 *
 * The two sessions intentionally have independent sockets and lifecycle state,
 * so a Digital Cluster failure cannot tear down Climate/Vehicle connectivity.
 */
public final class VehicleGatewayService extends Service
        implements GatewayConnection.Listener {

    private static final String TAG = "HN-VehicleGateway";
    private static final int MAX_REQUEST_ID_LENGTH = 96;

    private final AtomicLong nextCorrelation = new AtomicLong(1L);

    private final ConcurrentHashMap<Long, Pending> pendingByCorrelation =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Pending> pendingByRequestId =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Cached> resultCache =
            new ConcurrentHashMap<>();

    private final RemoteCallbackList<IVehicleStateListener> stateListeners =
            new RemoteCallbackList<>();

    private final ScheduledExecutorService timers =
            Executors.newSingleThreadScheduledExecutor();

    private volatile int connectionState =
            VehicleGatewayContract.CONNECTION_DISCONNECTED;

    private volatile VehicleState latestState =
            disconnectedState();

    /*
     * Existing HNVG/6100 vehicle connection.
     */
    private GatewayConnection connection;

    /*
     * Dedicated HNCL/6200 Digital Cluster connection.
     */
    private ClusterNavigationConnection clusterConnection;

    /*
     * Navigation AIDL -> HNCL adapter.
     */
    private NavigationClusterBridge navigationClusterBridge;

    private final IVehicleGatewayService.Stub binder =
            new IVehicleGatewayService.Stub() {

                @Override
                public int getApiVersion() {
                    enforceTrustedCaller();
                    return VehicleGatewayContract.API_VERSION;
                }

                @Override
                public int getConnectionState() {
                    enforceTrustedCaller();
                    return connectionState;
                }

                @Override
                public VehicleState getLatestVehicleState() {
                    enforceTrustedCaller();
                    return latestState;
                }

                @Override
                public void submitClimateCommand(
                        VehicleClimateCommand command,
                        IVehicleGatewayCallback callback
                ) {
                    enforceTrustedCaller();

                    if (callback == null) {
                        return;
                    }

                    String validation = validate(command);

                    if (validation != null) {
                        deliver(
                                callback,
                                result(
                                        command == null
                                                ? ""
                                                : command.getRequestId(),
                                        HyperNovaContract.STATUS_REJECTED,
                                        VehicleGatewayContract
                                                .ERROR_INVALID_ARGUMENT,
                                        validation
                                )
                        );
                        return;
                    }

                    pruneCache();

                    Cached cached =
                            resultCache.get(command.getRequestId());

                    if (cached != null) {
                        deliver(callback, cached.result);
                        return;
                    }

                    Pending existing =
                            pendingByRequestId.get(
                                    command.getRequestId()
                            );

                    if (existing != null) {
                        existing.callbacks.addIfAbsent(callback);
                        return;
                    }

                    if (!connection.isReady()) {
                        deliver(
                                callback,
                                result(
                                        command.getRequestId(),
                                        HyperNovaContract
                                                .STATUS_UNAVAILABLE,
                                        VehicleGatewayContract
                                                .ERROR_SERVICE_UNAVAILABLE,
                                        "Vehicle gateway is unavailable"
                                )
                        );
                        return;
                    }

                    long correlation = allocateCorrelation();

                    Pending pending =
                            new Pending(
                                    correlation,
                                    command,
                                    callback
                            );

                    pendingByCorrelation.put(
                            correlation,
                            pending
                    );

                    pendingByRequestId.put(
                            command.getRequestId(),
                            pending
                    );

                    if (!connection.submitClimate(
                            correlation,
                            command.getTargetTemperatureC(),
                            command.getFanLevel(),
                            command.getZone(),
                            command.getCaller()
                    )) {
                        finish(
                                pending,
                                result(
                                        command.getRequestId(),
                                        HyperNovaContract
                                                .STATUS_UNAVAILABLE,
                                        VehicleGatewayContract
                                                .ERROR_SERVICE_UNAVAILABLE,
                                        "Vehicle gateway send queue is unavailable"
                                )
                        );
                        return;
                    }

                    timers.schedule(
                            () -> timeout(correlation),
                            VehicleGatewayContract
                                    .COMMAND_TIMEOUT_MILLIS,
                            TimeUnit.MILLISECONDS
                    );
                }

                @Override
                public void registerVehicleStateListener(
                        IVehicleStateListener listener
                ) {
                    enforceTrustedCaller();

                    if (listener == null) {
                        return;
                    }

                    stateListeners.register(listener);

                    try {
                        listener.onVehicleState(latestState);
                    } catch (RemoteException ignored) {
                    }
                }

                @Override
                public void unregisterVehicleStateListener(
                        IVehicleStateListener listener
                ) {
                    enforceTrustedCaller();

                    if (listener != null) {
                        stateListeners.unregister(listener);
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();

        /*
         * Existing Climate / Vehicle / TC397 transport.
         */
        connection =
                new GatewayConnection(
                        BuildConfig.GATEWAY_HOST,
                        BuildConfig.GATEWAY_PORT,
                        this
                );

        /*
         * Independent Digital Cluster transport.
         */
        clusterConnection =
                new ClusterNavigationConnection(
                        BuildConfig.CLUSTER_HOST,
                        BuildConfig.CLUSTER_PORT,
                        connected ->
                                Log.i(
                                        TAG,
                                        connected
                                                ? "QNX Digital Cluster HNCL link ready"
                                                : "QNX Digital Cluster HNCL link disconnected"
                                )
                );

        /*
         * Subscribe to the existing HyperNova Navigation AIDL status stream
         * and translate it to HNCL presentation frames.
         */
        navigationClusterBridge =
                new NavigationClusterBridge(
                        this,
                        clusterConnection
                );

        if (BuildConfig.ALLOW_PLAINTEXT_GATEWAY) {

            /*
             * HNVG and HNCL are both allowed on the isolated development
             * bench in debug builds.
             */
            connection.start();
            clusterConnection.start();
            navigationClusterBridge.start();

            Log.i(
                    TAG,
                    "Development transports started: "
                            + "HNVG "
                            + BuildConfig.GATEWAY_HOST
                            + ":"
                            + BuildConfig.GATEWAY_PORT
                            + ", HNCL "
                            + BuildConfig.CLUSTER_HOST
                            + ":"
                            + BuildConfig.CLUSTER_PORT
            );
        } else {
            Log.e(
                    TAG,
                    "Release transports disabled until authenticated "
                            + "Android/QNX channels are provisioned"
            );
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return intent != null
                && VehicleGatewayContract.BIND_ACTION.equals(
                        intent.getAction()
                )
                ? binder
                : null;
    }

    @Override
    public void onDestroy() {

        /*
         * Stop Navigation callbacks first so no new presentation state can
         * enter the cluster connection during teardown.
         */
        if (navigationClusterBridge != null) {
            navigationClusterBridge.stop();
        }

        if (clusterConnection != null) {
            clusterConnection.stop();
        }

        if (connection != null) {
            connection.stop();
        }

        failAllPending("Vehicle gateway stopped");
        stateListeners.kill();
        timers.shutdownNow();

        super.onDestroy();
    }

    @Override
    public void onConnectionState(int state) {
        connectionState = state;

        latestState =
                copyWithConnection(
                        latestState,
                        state,
                        false
                );

        publishState(latestState);
    }

    @Override
    public void onDisconnected() {
        failAllPending(
                "QNX connection lost before TC397 confirmation"
        );
    }

    @Override
    public void onCommandResult(
            GatewayConnection.CommandResult networkResult
    ) {
        Pending pending =
                pendingByCorrelation.get(
                        networkResult.correlationId
                );

        if (pending == null) {
            Log.w(
                    TAG,
                    "Ignoring uncorrelated gateway result "
                            + networkResult.correlationId
            );
            return;
        }

        int status = networkResult.status;

        if (status == HyperNovaContract.STATUS_CONFIRMED) {
            latestState =
                    applyConfirmedCommand(
                            latestState,
                            networkResult
                    );

            publishState(latestState);
        }

        String error =
                mapRejectReason(
                        networkResult.rejectReason,
                        status
                );

        String message =
                resultMessage(
                        status,
                        networkResult.rejectReason
                );

        VehicleGatewayResult result =
                result(
                        pending.command.getRequestId(),
                        status,
                        error,
                        message
                );

        if (status == HyperNovaContract.STATUS_ACCEPTED) {
            pending.callbacks.forEach(
                    callback ->
                            deliver(
                                    callback,
                                    result
                            )
            );
        } else {
            finish(
                    pending,
                    result
            );
        }
    }

    @Override
    public void onVehicleState(VehicleState state) {
        latestState = state;
        connectionState = state.getConnectionState();
        publishState(state);
    }

    @Override
    public void onFault(VehicleFaultEvent event) {
        int count = stateListeners.beginBroadcast();

        try {
            for (int i = 0; i < count; i++) {
                try {
                    stateListeners
                            .getBroadcastItem(i)
                            .onFault(event);
                } catch (RemoteException ignored) {
                }
            }
        } finally {
            stateListeners.finishBroadcast();
        }

        connection.requestState();
    }

    private void finish(
            Pending pending,
            VehicleGatewayResult result
    ) {
        if (!pendingByCorrelation.remove(
                pending.correlationId,
                pending
        )) {
            return;
        }

        pendingByRequestId.remove(
                pending.command.getRequestId(),
                pending
        );

        if (result.getStatus()
                != HyperNovaContract.STATUS_ACCEPTED) {

            resultCache.put(
                    pending.command.getRequestId(),
                    new Cached(
                            result,
                            SystemClock.elapsedRealtime()
                    )
            );
        }

        pending.callbacks.forEach(
                callback ->
                        deliver(
                                callback,
                                result
                        )
        );
    }

    private void timeout(long correlation) {
        Pending pending =
                pendingByCorrelation.get(correlation);

        if (pending == null) {
            return;
        }

        finish(
                pending,
                result(
                        pending.command.getRequestId(),
                        HyperNovaContract.STATUS_TIMEOUT,
                        VehicleGatewayContract.ERROR_TIMEOUT,
                        "TC397 confirmation timed out"
                )
        );
    }

    private void failAllPending(String message) {
        List<Pending> snapshot =
                new ArrayList<>(
                        pendingByCorrelation.values()
                );

        for (Pending pending : snapshot) {
            finish(
                    pending,
                    result(
                            pending.command.getRequestId(),
                            HyperNovaContract
                                    .STATUS_UNAVAILABLE,
                            VehicleGatewayContract
                                    .ERROR_SERVICE_UNAVAILABLE,
                            message
                    )
            );
        }
    }

    private void publishState(VehicleState state) {
        int count = stateListeners.beginBroadcast();

        try {
            for (int i = 0; i < count; i++) {
                try {
                    stateListeners
                            .getBroadcastItem(i)
                            .onVehicleState(state);
                } catch (RemoteException ignored) {
                }
            }
        } finally {
            stateListeners.finishBroadcast();
        }
    }

    private void enforceTrustedCaller() {
        int caller = Binder.getCallingUid();

        if (caller == Process.myUid()) {
            return;
        }

        if (getPackageManager().checkSignatures(
                Process.myUid(),
                caller
        ) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException(
                    "Vehicle Gateway caller is not signed by HyperNova"
            );
        }
    }

    private String validate(
            VehicleClimateCommand command
    ) {
        if (command == null) {
            return "Command is required";
        }

        String id = command.getRequestId();

        if (id == null
                || id.isBlank()
                || id.length() > MAX_REQUEST_ID_LENGTH) {
            return "requestId must contain 1..96 characters";
        }

        if (command.getFanLevel()
                < VehicleGatewayContract.MIN_FAN_LEVEL
                || command.getFanLevel()
                > VehicleGatewayContract.MAX_FAN_LEVEL) {
            return "Fan level must be between 0 and 5";
        }

        if (command.getFanLevel() > 0
                && (
                        command.getTargetTemperatureC()
                                < VehicleGatewayContract
                                        .MIN_TARGET_TEMPERATURE_C
                        || command.getTargetTemperatureC()
                                > VehicleGatewayContract
                                        .MAX_TARGET_TEMPERATURE_C
                )) {
            return "Target temperature must be between 16 and 28 C";
        }

        if (command.getZone()
                < VehicleGatewayContract.ZONE_BOTH
                || command.getZone()
                > VehicleGatewayContract.ZONE_PASSENGER) {
            return "Unsupported climate zone";
        }

        if (command.getCaller()
                != VehicleGatewayContract.CALLER_DRIVER
                && command.getCaller()
                != VehicleGatewayContract.CALLER_AI) {
            return "Unsupported caller";
        }

        return null;
    }

    private long allocateCorrelation() {
        while (true) {
            long value =
                    nextCorrelation.getAndUpdate(
                            current ->
                                    current >= 0xFFFF_FFFEL
                                            ? 1L
                                            : current + 1L
                    );

            if (value != 0
                    && !pendingByCorrelation
                            .containsKey(value)) {
                return value;
            }
        }
    }

    private VehicleGatewayResult result(
            String requestId,
            int status,
            String error,
            String message
    ) {
        return new VehicleGatewayResult(
                requestId,
                status,
                error,
                message,
                latestState
        );
    }

    private void deliver(
            IVehicleGatewayCallback callback,
            VehicleGatewayResult result
    ) {
        try {
            callback.onResult(result);
        } catch (RemoteException ignored) {
        }
    }

    private String mapRejectReason(
            int reason,
            int status
    ) {
        if (status != HyperNovaContract.STATUS_REJECTED) {

            if (status == HyperNovaContract.STATUS_TIMEOUT) {
                return VehicleGatewayContract.ERROR_TIMEOUT;
            }

            if (status
                    == HyperNovaContract.STATUS_UNAVAILABLE) {
                return VehicleGatewayContract
                        .ERROR_SERVICE_UNAVAILABLE;
            }

            return VehicleGatewayContract.ERROR_NONE;
        }

        switch (reason) {
            case 0xE1:
                return VehicleGatewayContract.ERROR_BUSY;

            case 0xE2:
                return VehicleGatewayContract
                        .ERROR_SERVICE_UNAVAILABLE;

            case 0xE3:
                return VehicleGatewayContract
                        .ERROR_INVALID_ARGUMENT;

            case 0xE4:
                return VehicleGatewayContract.ERROR_TIMEOUT;

            case 0x01:
                return VehicleGatewayContract
                        .ERROR_TC397_UNKNOWN_COMMAND;

            case 0x02:
                return VehicleGatewayContract
                        .ERROR_TC397_INVALID_LENGTH;

            case 0x03:
                return VehicleGatewayContract
                        .ERROR_TC397_INVALID_PARAMETER;

            case 0x04:
                return VehicleGatewayContract
                        .ERROR_TC397_SAFETY_BLOCKED;

            case 0x05:
                return VehicleGatewayContract
                        .ERROR_TC397_SYSTEM_FAULT;

            case 0x06:
                return VehicleGatewayContract
                        .ERROR_TC397_HARDWARE_FAULT;

            case 0x07:
                return VehicleGatewayContract
                        .ERROR_TC397_OVERHEAT;

            case 0x08:
                return VehicleGatewayContract
                        .ERROR_TC397_SENSOR_FAULT;

            case 0x09:
                return VehicleGatewayContract
                        .ERROR_TC397_NOT_READY;

            default:
                return VehicleGatewayContract.ERROR_PROTOCOL;
        }
    }

    private String resultMessage(
            int status,
            int reason
    ) {
        switch (status) {

            case HyperNovaContract.STATUS_ACCEPTED:
                return "Climate request accepted by QNX";

            case HyperNovaContract.STATUS_CONFIRMED:
                return "Climate request confirmed by TC397";

            case HyperNovaContract.STATUS_REJECTED:
                if (reason == 0xE1) {
                    return "Another climate request is already in progress";
                }

                if (reason == 0xE3) {
                    return "QNX rejected an invalid climate request";
                }

                return "TC397 rejected the climate request "
                        + "(reason "
                        + reason
                        + ")";

            case HyperNovaContract.STATUS_TIMEOUT:
                return "TC397 response timed out";

            default:
                return "Vehicle gateway unavailable";
        }
    }

    private void pruneCache() {
        long cutoff =
                SystemClock.elapsedRealtime()
                        - HyperNovaContract
                                .REQUEST_DEDUP_TTL_MILLIS;

        for (Map.Entry<String, Cached> entry
                : resultCache.entrySet()) {

            if (entry.getValue().storedAtElapsedMillis
                    < cutoff) {

                resultCache.remove(
                        entry.getKey(),
                        entry.getValue()
                );
            }
        }
    }

    private static VehicleState disconnectedState() {
        return new VehicleState(
                VehicleGatewayContract
                        .CONNECTION_DISCONNECTED,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                new int[0],
                false,
                0L
        );
    }

    private static VehicleState copyWithConnection(
            VehicleState state,
            int connection,
            boolean fresh
    ) {
        return new VehicleState(
                connection,
                state.getCabinTemperatureC(),
                state.getHumidityPercent(),
                state.getFuelPercent(),
                state.getZone1TargetTemperatureC(),
                state.getZone2TargetTemperatureC(),
                state.getZone1FanLevel(),
                state.getZone2FanLevel(),
                state.getActiveDtcs(),
                fresh,
                state.getUpdatedAtEpochMillis()
        );
    }

    private static VehicleState applyConfirmedCommand(
            VehicleState state,
            GatewayConnection.CommandResult result
    ) {
        int zone1Target =
                state.getZone1TargetTemperatureC();

        int zone2Target =
                state.getZone2TargetTemperatureC();

        int zone1Fan =
                state.getZone1FanLevel();

        int zone2Fan =
                state.getZone2FanLevel();

        if (result.zone
                == VehicleGatewayContract.ZONE_BOTH
                || result.zone
                == VehicleGatewayContract.ZONE_DRIVER) {

            if (result.fanLevel > 0) {
                zone1Target =
                        result.targetTemperatureC;
            }

            zone1Fan = result.fanLevel;
        }

        if (result.zone
                == VehicleGatewayContract.ZONE_BOTH
                || result.zone
                == VehicleGatewayContract.ZONE_PASSENGER) {

            if (result.fanLevel > 0) {
                zone2Target =
                        result.targetTemperatureC;
            }

            zone2Fan = result.fanLevel;
        }

        return new VehicleState(
                state.getConnectionState(),
                state.getCabinTemperatureC(),
                state.getHumidityPercent(),
                state.getFuelPercent(),
                zone1Target,
                zone2Target,
                zone1Fan,
                zone2Fan,
                state.getActiveDtcs(),
                state.isTelemetryFresh(),
                state.getUpdatedAtEpochMillis()
        );
    }

    private static final class Pending {
        final long correlationId;
        final VehicleClimateCommand command;

        final CopyOnWriteArrayList<IVehicleGatewayCallback>
                callbacks =
                new CopyOnWriteArrayList<>();

        Pending(
                long correlationId,
                VehicleClimateCommand command,
                IVehicleGatewayCallback callback
        ) {
            this.correlationId = correlationId;
            this.command = command;
            callbacks.add(callback);
        }
    }

    private static final class Cached {
        final VehicleGatewayResult result;
        final long storedAtElapsedMillis;

        Cached(
                VehicleGatewayResult result,
                long storedAtElapsedMillis
        ) {
            this.result = result;
            this.storedAtElapsedMillis =
                    storedAtElapsedMillis;
        }
    }
}
