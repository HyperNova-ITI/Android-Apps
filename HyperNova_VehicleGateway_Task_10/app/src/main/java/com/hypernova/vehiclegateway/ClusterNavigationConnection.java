package com.hypernova.vehiclegateway;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the dedicated Android-to-QNX Digital Cluster navigation session.
 *
 * This connection is completely independent from GatewayConnection/HNVG.
 *
 * Existing vehicle path:
 *   GatewayConnection -> HNVG -> TCP 6100
 *
 * Cluster navigation path:
 *   ClusterNavigationConnection -> HNCL -> TCP 6200
 *
 * Navigation presentation updates are coalesced: only the newest state is
 * retained while the socket is busy or disconnected. This prevents stale
 * route-position updates from accumulating in a queue.
 */
final class ClusterNavigationConnection {

    interface Listener {
        void onClusterConnectionState(boolean connected);
    }

    private static final String TAG = "HN-ClusterLink";

    private static final int CONNECT_TIMEOUT_MILLIS = 1_500;
    private static final int READ_TIMEOUT_MILLIS = 100;
    private static final int MAX_RX_BUFFER = 8_192;

    private static final long HEARTBEAT_MILLIS = 2_000L;
    private static final long INITIAL_BACKOFF_MILLIS = 250L;
    private static final long MAX_BACKOFF_MILLIS = 5_000L;

    private static final int NAVIGATION_CAPABILITY = 0x00000001;

    private final String host;
    private final int port;
    private final Listener listener;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Last known cluster presentation state.
     *
     * It is intentionally retained across reconnects so QNX can immediately
     * reconstruct the current navigation screen after a cluster restart.
     */
    private final AtomicReference<byte[]> latestPresentation =
            new AtomicReference<>();

    /**
     * Next presentation frame waiting to be written.
     *
     * New navigation updates replace older unsent updates.
     */
    private final AtomicReference<byte[]> pendingPresentation =
            new AtomicReference<>();

    private volatile boolean ready;
    private volatile Socket socket;
    private Thread worker;

    ClusterNavigationConnection(
            String host,
            int port,
            Listener listener
    ) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        worker = new Thread(
                this::supervise,
                "hypernova-cluster-navigation-link"
        );
        worker.start();
    }

    void stop() {
        running.set(false);
        closeSocket();

        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }

        pendingPresentation.set(null);
        ready = false;
        notifyConnectionState(false);
    }

    boolean isReady() {
        return ready;
    }

    /**
     * Publish the newest navigation presentation state.
     *
     * This does not perform socket I/O on the caller thread.
     */
    void publishNavigationState(
            boolean active,
            int maneuver,
            int speedLimitKph,
            long distanceToManeuverMeters,
            long remainingDistanceMeters,
            long remainingTimeSeconds,
            long etaEpochSeconds,
            double latitude,
            double longitude,
            float headingDegrees,
            String streetName,
            String destination
    ) {
        byte[] payload = ClusterNavigationProtocol.navigationStatePayload(
                active,
                maneuver,
                speedLimitKph,
                distanceToManeuverMeters,
                remainingDistanceMeters,
                remainingTimeSeconds,
                etaEpochSeconds,
                latitude,
                longitude,
                headingDegrees,
                streetName,
                destination
        );

        byte[] frame = ClusterNavigationProtocol.encode(
                ClusterNavigationProtocol.TYPE_NAVIGATION_STATE,
                0,
                payload
        );

        latestPresentation.set(frame);
        pendingPresentation.set(frame);
    }

    /**
     * Clear navigation presentation from the cluster.
     *
     * The clear frame is retained as the current presentation state so that
     * a reconnect does not accidentally restore an old route.
     */
    void clearNavigation() {
        byte[] frame = ClusterNavigationProtocol.encode(
                ClusterNavigationProtocol.TYPE_NAVIGATION_CLEAR,
                0,
                null
        );

        latestPresentation.set(frame);
        pendingPresentation.set(frame);
    }

    private void supervise() {
        long backoffMillis = INITIAL_BACKOFF_MILLIS;

        while (running.get()) {
            notifyConnectionState(false);

            try (Socket connected = new Socket()) {
                Log.i(
                        TAG,
                        "Connecting to QNX cluster at "
                                + host
                                + ":"
                                + port
                );

                connected.connect(
                        new InetSocketAddress(host, port),
                        CONNECT_TIMEOUT_MILLIS
                );

                connected.setTcpNoDelay(true);
                connected.setKeepAlive(true);
                connected.setSoTimeout(READ_TIMEOUT_MILLIS);

                socket = connected;
                ready = false;

                runSession(connected);

                backoffMillis = INITIAL_BACKOFF_MILLIS;
            } catch (
                    IOException
                    | ClusterNavigationProtocol.ProtocolException error
            ) {
                if (running.get()) {
                    Log.w(
                            TAG,
                            "Cluster session ended: " + error.getMessage()
                    );
                }
            } finally {
                ready = false;
                socket = null;
                pendingPresentation.set(null);
                notifyConnectionState(false);
            }

            if (!running.get()) {
                break;
            }

            try {
                Thread.sleep(backoffMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            backoffMillis = Math.min(
                    backoffMillis * 2L,
                    MAX_BACKOFF_MILLIS
            );
        }
    }

    private void runSession(Socket connected)
            throws IOException,
            ClusterNavigationProtocol.ProtocolException {

        InputStream input = connected.getInputStream();
        OutputStream output = connected.getOutputStream();

        /*
         * Handshake first. Navigation data is not transmitted until QNX
         * confirms the HNCL protocol version.
         */
        output.write(
                ClusterNavigationProtocol.encode(
                        ClusterNavigationProtocol.TYPE_HELLO,
                        0,
                        ClusterNavigationProtocol.helloPayload()
                )
        );
        output.flush();

        byte[] receive = new byte[MAX_RX_BUFFER];
        int receiveLength = 0;

        long lastHeartbeat = System.currentTimeMillis();

        while (running.get() && !connected.isClosed()) {

            /*
             * Only transmit navigation presentation after HELLO_ACK.
             *
             * getAndSet(null) means an update arriving during this write
             * remains pending for the next iteration.
             */
            if (ready) {
                byte[] frame = pendingPresentation.getAndSet(null);

                if (frame != null) {
                    output.write(frame);
                    output.flush();
                }
            }

            long now = System.currentTimeMillis();

            if (now - lastHeartbeat >= HEARTBEAT_MILLIS) {
                output.write(
                        ClusterNavigationProtocol.encode(
                                ClusterNavigationProtocol.TYPE_PING,
                                0,
                                null
                        )
                );
                output.flush();
                lastHeartbeat = now;
            }

            try {
                int count = input.read(
                        receive,
                        receiveLength,
                        receive.length - receiveLength
                );

                if (count < 0) {
                    throw new IOException(
                            "QNX cluster closed the connection"
                    );
                }

                receiveLength += count;
            } catch (SocketTimeoutException ignored) {
                /*
                 * Timeout intentionally keeps stop requests, outgoing state
                 * updates, and heartbeats responsive.
                 */
            }

            int consumed = 0;

            while (consumed < receiveLength) {
                byte[] remaining = Arrays.copyOfRange(
                        receive,
                        consumed,
                        receiveLength
                );

                ClusterNavigationProtocol.DecodeResult decoded =
                        ClusterNavigationProtocol.tryDecode(
                                remaining,
                                remaining.length
                        );

                if (decoded == null) {
                    break;
                }

                handle(decoded.frame);
                consumed += decoded.consumed;
            }

            if (consumed > 0) {
                System.arraycopy(
                        receive,
                        consumed,
                        receive,
                        0,
                        receiveLength - consumed
                );

                receiveLength -= consumed;
            }

            if (receiveLength == receive.length) {
                throw new ClusterNavigationProtocol.ProtocolException(
                        "cluster receive buffer exhausted"
                );
            }
        }
    }

    private void handle(ClusterNavigationProtocol.Frame frame)
            throws ClusterNavigationProtocol.ProtocolException {

        ByteBuffer payload = ByteBuffer
                .wrap(frame.payload)
                .order(ByteOrder.BIG_ENDIAN);

        switch (frame.type) {
            case ClusterNavigationProtocol.TYPE_HELLO_ACK:
                requireLength(frame, 6);

                int apiVersion = payload.getShort() & 0xFFFF;
                int capabilities = payload.getInt();

                if (apiVersion != ClusterNavigationProtocol.VERSION) {
                    throw new ClusterNavigationProtocol.ProtocolException(
                            "QNX cluster API version mismatch"
                    );
                }

                if ((capabilities & NAVIGATION_CAPABILITY) == 0) {
                    throw new ClusterNavigationProtocol.ProtocolException(
                            "QNX cluster does not advertise navigation capability"
                    );
                }

                ready = true;

                /*
                 * Immediately restore current presentation after reconnect.
                 */
                pendingPresentation.set(latestPresentation.get());

                notifyConnectionState(true);

                Log.i(
                        TAG,
                        "HNCL cluster session ready"
                );
                break;

            case ClusterNavigationProtocol.TYPE_PONG:
                requireLength(frame, 0);
                break;

            default:
                throw new ClusterNavigationProtocol.ProtocolException(
                        "unexpected cluster message type " + frame.type
                );
        }
    }

    private void requireLength(
            ClusterNavigationProtocol.Frame frame,
            int expected
    ) throws ClusterNavigationProtocol.ProtocolException {

        if (frame.payload.length != expected) {
            throw new ClusterNavigationProtocol.ProtocolException(
                    "invalid payload length for type "
                            + frame.type
                            + ": expected "
                            + expected
                            + ", received "
                            + frame.payload.length
            );
        }
    }

    private void notifyConnectionState(boolean connected) {
        if (listener != null) {
            listener.onClusterConnectionState(connected);
        }
    }

    private void closeSocket() {
        Socket current = socket;

        if (current == null) {
            return;
        }

        try {
            current.close();
        } catch (IOException ignored) {
            // Best effort during shutdown/reconnect.
        }
    }
}
