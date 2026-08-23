package com.hypernova.vehiclegateway;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the dedicated Android-to-QNX Digital Cluster media session (HNMC).
 *
 * This connection is completely independent from GatewayConnection/HNVG and
 * from ClusterNavigationConnection/HNCL:
 *
 *   ClusterMediaConnection -> HNMC -> TCP (default 6300, constructor supplied)
 *
 * Media presentation updates are coalesced: only the newest state is retained
 * while the socket is busy or disconnected. The latest state (including an
 * explicit clear) survives reconnects and is re-sent once QNX confirms the
 * HELLO_ACK handshake. HNMC failures never affect other gateway transports.
 */
final class ClusterMediaConnection {

    interface Listener {
        void onClusterConnectionState(boolean connected);
    }

    private static final Logger LOGGER = Logger.getLogger(ClusterMediaConnection.class.getName());

    private static final int CONNECT_TIMEOUT_MILLIS = 1_500;
    private static final int READ_TIMEOUT_MILLIS = 100;
    private static final int MAX_RX_BUFFER =
            ClusterMediaProtocol.HEADER_SIZE + ClusterMediaProtocol.MAX_PAYLOAD;

    private static final long HEARTBEAT_MILLIS = 2_000L;
    private static final long INITIAL_BACKOFF_MILLIS = 250L;
    private static final long MAX_BACKOFF_MILLIS = 5_000L;

    private final String host;
    private final int port;
    private final Listener listener;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Last known media presentation state.
     *
     * It is intentionally retained across reconnects so QNX can immediately
     * reconstruct the current media screen after a cluster restart.
     */
    private final AtomicReference<Outgoing> latestPresentation =
            new AtomicReference<>();

    /**
     * Next presentation frame waiting to be written.
     *
     * New media updates replace older unsent updates.
     */
    private final AtomicReference<Outgoing> pendingPresentation =
            new AtomicReference<>();

    /** Monotonic publication sequence backing strict newest-wins updates. */
    private final AtomicLong publishSequence = new AtomicLong();

    private volatile boolean ready;
    private volatile Socket socket;
    private Thread worker;

    ClusterMediaConnection(
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
                "hypernova-cluster-media-link"
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
     * Publish the newest media presentation state.
     *
     * When hasMedia is false a zero-payload MEDIA_CLEAR is preferred over a
     * MEDIA_STATE frame. This does not perform socket I/O on the caller thread.
     */
    void publishMediaState(
            boolean hasMedia,
            boolean playing,
            long positionMs,
            long durationMs,
            String mediaId,
            String title,
            String artist,
            String album
    ) {
        byte[] frame;

        if (hasMedia) {
            frame = ClusterMediaProtocol.encode(
                    ClusterMediaProtocol.TYPE_MEDIA_STATE,
                    0,
                    ClusterMediaProtocol.mediaStatePayload(
                            hasMedia,
                            playing,
                            positionMs,
                            durationMs,
                            mediaId,
                            title,
                            artist,
                            album
                    )
            );
        } else {
            frame = ClusterMediaProtocol.encode(
                    ClusterMediaProtocol.TYPE_MEDIA_CLEAR,
                    0,
                    null
            );
        }

        storePresentation(frame);
    }

    /**
     * Clear media presentation from the cluster.
     *
     * The clear frame is retained as the current presentation state so that
     * a reconnect does not accidentally restore old media.
     */
    void clearMedia() {
        byte[] frame = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_MEDIA_CLEAR,
                0,
                null
        );

        storePresentation(frame);
    }

    private void storePresentation(byte[] frame) {
        Outgoing outgoing =
                new Outgoing(publishSequence.incrementAndGet(), frame);

        offerNewest(latestPresentation, outgoing);
        offerNewest(pendingPresentation, outgoing);
    }

    /**
     * Installs candidate into slot unless an equally or more recent frame is
     * already present.
     *
     * Concurrent publishers converge on the highest sequence, so strict
     * latest-state-wins holds even when updates race each other.
     */
    static void offerNewest(AtomicReference<Outgoing> slot, Outgoing candidate) {
        for (;;) {
            Outgoing current = slot.get();

            if (current != null && current.sequence >= candidate.sequence) {
                return;
            }

            if (slot.compareAndSet(current, candidate)) {
                return;
            }
        }
    }

    /**
     * HELLO_ACK handoff: atomically offers the newest known presentation as
     * the pending frame.
     *
     * Regression guard for reconnect restoration: a publication racing the
     * handshake keeps its newer frame instead of being overwritten by an
     * older latest-state snapshot.
     */
    static void mergeLatestIntoPending(
            AtomicReference<Outgoing> pending,
            AtomicReference<Outgoing> latest
    ) {
        Outgoing candidate = latest.get();

        if (candidate != null) {
            offerNewest(pending, candidate);
        }
    }

    private void supervise() {
        long backoffMillis = INITIAL_BACKOFF_MILLIS;

        while (running.get()) {
            notifyConnectionState(false);

            try (Socket connected = new Socket()) {
                LOGGER.fine(
                        "Connecting to QNX cluster media at "
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
                    | ClusterMediaProtocol.ProtocolException error
            ) {
                if (running.get()) {
                    LOGGER.log(
                            Level.WARNING,
                            "Cluster media session ended: " + error.getMessage()
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
            ClusterMediaProtocol.ProtocolException {

        InputStream input = connected.getInputStream();
        OutputStream output = connected.getOutputStream();

        /*
         * Handshake first. Media data is not transmitted until QNX confirms
         * the HNMC protocol version and media capability.
         */
        output.write(
                ClusterMediaProtocol.encode(
                        ClusterMediaProtocol.TYPE_HELLO,
                        0,
                        ClusterMediaProtocol.helloPayload()
                )
        );
        output.flush();

        byte[] receive = new byte[MAX_RX_BUFFER];
        int receiveLength = 0;

        long lastHeartbeat = System.currentTimeMillis();

        while (running.get() && !connected.isClosed()) {

            /*
             * Only transmit media presentation after HELLO_ACK.
             *
             * getAndSet(null) means an update arriving during this write
             * remains pending for the next iteration.
             */
            if (ready) {
                Outgoing outgoing = pendingPresentation.getAndSet(null);

                if (outgoing != null) {
                    output.write(outgoing.frame);
                    output.flush();
                }
            }

            long now = System.currentTimeMillis();

            if (now - lastHeartbeat >= HEARTBEAT_MILLIS) {
                output.write(
                        ClusterMediaProtocol.encode(
                                ClusterMediaProtocol.TYPE_PING,
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
                            "QNX cluster closed the media connection"
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

                ClusterMediaProtocol.DecodeResult decoded =
                        ClusterMediaProtocol.tryDecode(
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
                throw new ClusterMediaProtocol.ProtocolException(
                        "cluster media receive buffer exhausted"
                );
            }
        }
    }

    private void handle(ClusterMediaProtocol.Frame frame)
            throws ClusterMediaProtocol.ProtocolException {

        switch (frame.type) {
            case ClusterMediaProtocol.TYPE_HELLO_ACK:
                ClusterMediaProtocol.validateHelloAck(frame.payload);

                /*
                 * Restore the current presentation BEFORE going ready so no
                 * send can slip between readiness and restoration.
                 *
                 * mergeLatestIntoPending is an atomic newest-wins merge: a
                 * publication racing this handshake keeps its newer frame
                 * instead of being clobbered by a stale snapshot read here.
                 */
                pendingPresentation.set(latestPresentation.get());

                ready = true;

                notifyConnectionState(true);

                LOGGER.fine("HNMC cluster media session ready");
                break;

            case ClusterMediaProtocol.TYPE_PONG:
                if (frame.payload.length != 0) {
                    throw new ClusterMediaProtocol.ProtocolException(
                            "invalid PONG payload length: "
                                    + frame.payload.length
                    );
                }
                break;

            default:
                throw new ClusterMediaProtocol.ProtocolException(
                        "unexpected cluster media message type " + frame.type
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

    /**
     * Presentation frame tagged with its publication sequence so that
     * concurrent publishers and the handshake restore resolve strictly
     * newest-wins.
     */
    static final class Outgoing {
        final long sequence;
        final byte[] frame;

        Outgoing(long sequence, byte[] frame) {
            this.sequence = sequence;
            this.frame = frame;
        }
    }
}
