package com.hypernova.vehiclegateway;

import android.util.Log;

import com.hypernova.contracts.vehiclegateway.VehicleFaultEvent;
import com.hypernova.contracts.vehiclegateway.VehicleGatewayContract;
import com.hypernova.contracts.vehiclegateway.VehicleState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the one Android-to-QNX HNVG session. No Binder thread performs socket I/O. */
final class GatewayConnection {
    interface Listener {
        void onConnectionState(int state);
        void onDisconnected();
        void onCommandResult(CommandResult result);
        void onVehicleState(VehicleState state);
        void onFault(VehicleFaultEvent event);
    }

    static final class CommandResult {
        final long correlationId;
        final int commandType;
        final int status;
        final int rejectReason;
        final int targetTemperatureC;
        final int fanLevel;
        final int zone;
        final int caller;
        final int tcSequence;

        CommandResult(long correlationId, ByteBuffer payload) {
            this.correlationId = correlationId;
            commandType = payload.get() & 0xFF;
            status = payload.get() & 0xFF;
            rejectReason = payload.get() & 0xFF;
            payload.get(); // reserved
            targetTemperatureC = payload.get();
            fanLevel = payload.get() & 0xFF;
            zone = payload.get() & 0xFF;
            caller = payload.get() & 0xFF;
            tcSequence = payload.get() & 0xFF;
        }
    }

    private static final String TAG = "HN-GatewayLink";
    private static final int CONNECT_TIMEOUT_MILLIS = 1_500;
    private static final int READ_TIMEOUT_MILLIS = 100;
    private static final int MAX_RX_BUFFER = 4_096;
    private static final long HEARTBEAT_MILLIS = 2_000L;

    private final String host;
    private final int port;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final BlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(64);

    private volatile boolean ready;
    private volatile Socket socket;
    private Thread worker;

    GatewayConnection(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = new Thread(this::supervise, "hypernova-gateway-link");
        worker.start();
    }

    void stop() {
        running.set(false);
        closeSocket();
        Thread thread = worker;
        if (thread != null) thread.interrupt();
        outbound.clear();
        ready = false;
    }

    boolean isReady() {
        return ready;
    }

    boolean submitClimate(long correlationId, int target, int fan, int zone, int caller) {
        if (!ready || correlationId == 0) return false;
        return outbound.offer(GatewayProtocol.encode(
                GatewayProtocol.TYPE_SET_HVAC,
                correlationId,
                GatewayProtocol.climatePayload(target, fan, zone, caller)
        ));
    }

    void requestState() {
        if (ready) {
            outbound.offer(GatewayProtocol.encode(GatewayProtocol.TYPE_GET_STATE, 0, null));
        }
    }

    private void supervise() {
        long backoffMillis = 250L;
        while (running.get()) {
            listener.onConnectionState(VehicleGatewayContract.CONNECTION_CONNECTING);
            try (Socket connected = new Socket()) {
                connected.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
                connected.setTcpNoDelay(true);
                connected.setKeepAlive(true);
                connected.setSoTimeout(READ_TIMEOUT_MILLIS);
                socket = connected;
                outbound.clear();
                ready = false;
                runSession(connected);
                backoffMillis = 250L;
            } catch (IOException | GatewayProtocol.ProtocolException error) {
                if (running.get()) Log.w(TAG, "Gateway session ended: " + error.getMessage());
            } finally {
                ready = false;
                socket = null;
                outbound.clear();
                listener.onConnectionState(VehicleGatewayContract.CONNECTION_DISCONNECTED);
                listener.onDisconnected();
            }

            if (!running.get()) break;
            try {
                Thread.sleep(backoffMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            backoffMillis = Math.min(backoffMillis * 2L, 5_000L);
        }
    }

    private void runSession(Socket connected)
            throws IOException, GatewayProtocol.ProtocolException {
        InputStream input = connected.getInputStream();
        OutputStream output = connected.getOutputStream();
        output.write(GatewayProtocol.encode(
                GatewayProtocol.TYPE_HELLO,
                0,
                GatewayProtocol.helloPayload()
        ));
        output.flush();

        byte[] receive = new byte[MAX_RX_BUFFER];
        int receiveLength = 0;
        long lastHeartbeat = System.currentTimeMillis();

        while (running.get() && !connected.isClosed()) {
            byte[] frame;
            boolean wrote = false;
            while ((frame = outbound.poll()) != null) {
                output.write(frame);
                wrote = true;
            }
            if (wrote) output.flush();

            if (System.currentTimeMillis() - lastHeartbeat >= HEARTBEAT_MILLIS) {
                output.write(GatewayProtocol.encode(GatewayProtocol.TYPE_PING, 0, null));
                output.flush();
                lastHeartbeat = System.currentTimeMillis();
            }

            try {
                int count = input.read(receive, receiveLength, receive.length - receiveLength);
                if (count < 0) throw new IOException("QNX relay closed the connection");
                receiveLength += count;
            } catch (SocketTimeoutException ignored) {
                // The timeout keeps writes, stop requests, and heartbeats responsive.
            }

            int consumed = 0;
            while (consumed < receiveLength) {
                byte[] remaining = Arrays.copyOfRange(receive, consumed, receiveLength);
                GatewayProtocol.DecodeResult decoded =
                        GatewayProtocol.tryDecode(remaining, remaining.length);
                if (decoded == null) break;
                handle(decoded.frame);
                consumed += decoded.consumed;
            }
            if (consumed > 0) {
                System.arraycopy(receive, consumed, receive, 0, receiveLength - consumed);
                receiveLength -= consumed;
            }
            if (receiveLength == receive.length) {
                throw new GatewayProtocol.ProtocolException("receive buffer exhausted");
            }
        }
    }

    private void handle(GatewayProtocol.Frame frame) throws GatewayProtocol.ProtocolException {
        ByteBuffer payload = ByteBuffer.wrap(frame.payload).order(ByteOrder.BIG_ENDIAN);
        switch (frame.type) {
            case GatewayProtocol.TYPE_HELLO_ACK:
                requireLength(frame, 4);
                int apiVersion = payload.getShort() & 0xFFFF;
                payload.getShort(); // TC protocol version
                if (apiVersion != GatewayProtocol.VERSION) {
                    throw new GatewayProtocol.ProtocolException("QNX API version mismatch");
                }
                ready = true;
                listener.onConnectionState(VehicleGatewayContract.CONNECTION_CONNECTED);
                requestState();
                break;
            case GatewayProtocol.TYPE_PONG:
                requireLength(frame, 0);
                break;
            case GatewayProtocol.TYPE_COMMAND_RESULT:
                requireLength(frame, 12);
                listener.onCommandResult(new CommandResult(frame.correlationId, payload));
                break;
            case GatewayProtocol.TYPE_VEHICLE_STATE:
                requireLength(frame, 14);
                listener.onVehicleState(decodeState(payload));
                break;
            case GatewayProtocol.TYPE_FAULT_EVENT:
                requireLength(frame, 4);
                int dtc = payload.getShort() & 0xFFFF;
                boolean active = (payload.get() & 0xFF) == 1;
                int sequence = payload.get() & 0xFF;
                listener.onFault(new VehicleFaultEvent(
                        dtc,
                        active,
                        sequence,
                        System.currentTimeMillis()
                ));
                break;
            default:
                throw new GatewayProtocol.ProtocolException("unexpected message type " + frame.type);
        }
    }

    private VehicleState decodeState(ByteBuffer payload) {
        int cabinTemperature = payload.get();
        int humidity = unsignedOrUnknown(payload.get());
        int fuel = unsignedOrUnknown(payload.get());
        int zone1Target = payload.get();
        int zone2Target = payload.get();
        int zone1Fan = unsignedOrUnknown(payload.get());
        int zone2Fan = unsignedOrUnknown(payload.get());
        int dtcMask = payload.get() & 0xFF;
        int flags = payload.get() & 0xFF;
        long telemetryAge = Integer.toUnsignedLong(payload.getInt());
        payload.get(); // last TC event sequence, exposed through fault events

        boolean tcConnected = (flags & 0x01) != 0;
        boolean fresh = (flags & 0x02) != 0;
        int connectionState = tcConnected && fresh
                ? VehicleGatewayContract.CONNECTION_CONNECTED
                : VehicleGatewayContract.CONNECTION_DEGRADED;
        long updatedAt = telemetryAge == 0xFFFFFFFFL
                ? 0L
                : Math.max(0L, System.currentTimeMillis() - telemetryAge);

        return new VehicleState(
                connectionState,
                cabinTemperature,
                humidity,
                fuel,
                zone1Target,
                zone2Target,
                zone1Fan,
                zone2Fan,
                decodeDtcs(dtcMask),
                fresh,
                updatedAt
        );
    }

    private int unsignedOrUnknown(byte value) {
        int decoded = value & 0xFF;
        return decoded == 0xFF ? -1 : decoded;
    }

    private int[] decodeDtcs(int mask) {
        int[] known = {
                VehicleGatewayContract.DTC_P0217,
                VehicleGatewayContract.DTC_P0118,
                VehicleGatewayContract.DTC_P0300,
                VehicleGatewayContract.DTC_P0442,
                VehicleGatewayContract.DTC_P0562,
        };
        List<Integer> active = new ArrayList<>();
        for (int i = 0; i < known.length; i++) {
            if ((mask & (1 << i)) != 0) active.add(known[i]);
        }
        int[] result = new int[active.size()];
        for (int i = 0; i < active.size(); i++) result[i] = active.get(i);
        return result;
    }

    private void requireLength(GatewayProtocol.Frame frame, int expected)
            throws GatewayProtocol.ProtocolException {
        if (frame.payload.length != expected) {
            throw new GatewayProtocol.ProtocolException(
                    "invalid payload length for type " + frame.type
            );
        }
    }

    private void closeSocket() {
        Socket active = socket;
        if (active != null) {
            try {
                active.close();
            } catch (IOException ignored) {
            }
        }
    }
}
