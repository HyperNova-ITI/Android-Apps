package com.hypernova.vehiclegateway;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Focused integration-style unit tests for the HNMC media connection.
 *
 * They run against an in-process fake QNX media endpoint so no Gradle
 * configuration changes are needed (the connection avoids android.* APIs).
 */
public final class ClusterMediaConnectionTest {

    private static final int MAGIC_HNMC = 0x484E4D43;

    private static final int TYPE_HELLO = 0x01;
    private static final int TYPE_PING = 0x02;
    private static final int TYPE_MEDIA_STATE = 0x10;
    private static final int TYPE_MEDIA_CLEAR = 0x11;
    private static final int TYPE_HELLO_ACK = 0x81;
    private static final int TYPE_PONG = 0x82;

    /**
     * Exact HELLO wire frame expected from the gateway:
     * HNMC magic, version 1, type 0x01, reserved 0, payload 8, corr 0,
     * then uint16 version=1, uint16 reserved=0, uint32 MEDIA_STATE capability.
     */
    private static final byte[] EXPECTED_HELLO_FRAME = {
            0x48, 0x4E, 0x4D, 0x43,
            0x01,
            0x01,
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x08,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x01
    };

    /** HELLO_ACK advertising exactly the required MEDIA_STATE capability. */
    private static final byte[] HELLO_ACK_FRAME = {
            0x48, 0x4E, 0x4D, 0x43,
            0x01,
            (byte) 0x81,
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x08,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x01
    };

    private ServerSocket server;
    private Socket fakeCluster;
    private ClusterMediaConnection connection;
    private LinkedBlockingQueue<Boolean> readyEvents;

    @After
    public void tearDown() throws IOException {
        if (connection != null) {
            connection.stop();
        }

        if (fakeCluster != null && !fakeCluster.isClosed()) {
            fakeCluster.close();
        }

        if (server != null && !server.isClosed()) {
            server.close();
        }
    }

    @Test
    public void sendsExactHelloAndGatesMediaUntilHelloAck() throws Exception {
        startConnection();

        InputStream input = fakeCluster.getInputStream();
        OutputStream output = fakeCluster.getOutputStream();

        byte[] hello = new byte[EXPECTED_HELLO_FRAME.length];
        readFully(input, hello);
        assertArrayEquals(EXPECTED_HELLO_FRAME, hello);

        // Media published before the handshake must not reach the wire.
        connection.publishMediaState(
                true, true, 1_000L, 30_000L, "m1", "Title", "Artist", "Album");

        Thread.sleep(300);
        assertEquals(
                "MEDIA_STATE must not be sent before HELLO_ACK",
                0,
                input.available());

        output.write(HELLO_ACK_FRAME);
        output.flush();

        awaitReadyEvent();
        assertTrue(connection.isReady());

        WireFrame state = readFrameOfType(input, TYPE_MEDIA_STATE, 4);

        ByteBuffer payload = ByteBuffer
                .wrap(state.payload)
                .order(ByteOrder.BIG_ENDIAN);

        assertEquals(0x03, payload.get() & 0xFF);
        assertEquals(0, payload.get() & 0xFF);
        assertEquals(0, payload.get() & 0xFF);
        assertEquals(0, payload.get() & 0xFF);
        assertEquals(1_000L, payload.getLong());
        assertEquals(30_000L, payload.getLong());
        assertEquals(2, payload.getShort() & 0xFFFF);
        assertEquals(5, payload.getShort() & 0xFFFF);
        assertEquals(6, payload.getShort() & 0xFFFF);
        assertEquals(5, payload.getShort() & 0xFFFF);

        byte[] text = new byte[payload.remaining()];
        payload.get(text);
        assertEquals(
                "m1TitleArtistAlbum",
                new String(text, StandardCharsets.UTF_8));
    }

    @Test
    public void coalescesUnsentUpdatesToLatestState() throws Exception {
        startConnection();

        InputStream input = fakeCluster.getInputStream();
        OutputStream output = fakeCluster.getOutputStream();

        byte[] hello = new byte[EXPECTED_HELLO_FRAME.length];
        readFully(input, hello);

        connection.publishMediaState(true, true, 1, 10, "aa", "First", "", "");
        connection.publishMediaState(true, true, 2, 20, "bb", "Second", "", "");

        output.write(HELLO_ACK_FRAME);
        output.flush();

        awaitReadyEvent();

        WireFrame state = readFrameOfType(input, TYPE_MEDIA_STATE, 4);

        ByteBuffer payload = ByteBuffer
                .wrap(state.payload)
                .order(ByteOrder.BIG_ENDIAN);

        // Skip flags + 3 reserved bytes; position/duration are uint64s next.
        payload.position(4);

        long positionMs = payload.getLong();
        long durationMs = payload.getLong();
        int idLength = payload.getShort() & 0xFFFF;
        int titleLength = payload.getShort() & 0xFFFF;
        payload.getShort(); // artist length
        payload.getShort(); // album length

        byte[] text = new byte[idLength + titleLength];
        payload.get(text);

        String combined = new String(text, StandardCharsets.UTF_8);

        assertEquals(2L, positionMs);
        assertEquals(20L, durationMs);
        assertEquals("bbSecond", combined);

        // The superseded update must never appear on the wire.
        Thread.sleep(400);
        assertEquals(0, input.available());
    }

    @Test
    public void prefersMediaClearWhenHasMediaFalse() throws Exception {
        startConnection();

        InputStream input = fakeCluster.getInputStream();
        OutputStream output = fakeCluster.getOutputStream();

        readFully(input, new byte[EXPECTED_HELLO_FRAME.length]);

        connection.publishMediaState(
                false, true, 5, 5, "stale", "stale", "stale", "stale");

        output.write(HELLO_ACK_FRAME);
        output.flush();

        awaitReadyEvent();

        WireFrame clear = readFrameOfType(input, TYPE_MEDIA_CLEAR, 4);

        assertEquals(TYPE_MEDIA_CLEAR, clear.type);
        assertEquals(0, clear.payload.length);
    }

    @Test
    public void retainsLatestPresentationAcrossReconnect() throws Exception {
        openServer();
        startConnectionAsync();

        // Published before the first handshake completes; must survive both.
        connection.publishMediaState(
                true, true, 7_777L, 3_600_000L, "persist", "Same", "Track", "");

        InputStream firstInput = acceptFakeCluster().getInputStream();

        readFully(firstInput, new byte[EXPECTED_HELLO_FRAME.length]);
        writeHelloAck();

        awaitReadyEvent();

        byte[] firstPayload =
                readFrameOfType(firstInput, TYPE_MEDIA_STATE, 4).payload;

        // Force a remote disconnect; the client must reconnect and resend.
        fakeCluster.close();

        InputStream secondInput = acceptFakeCluster().getInputStream();

        byte[] secondHello = new byte[EXPECTED_HELLO_FRAME.length];
        readFully(secondInput, secondHello);
        assertArrayEquals(EXPECTED_HELLO_FRAME, secondHello);

        // fakeCluster now refers to the re-established session.
        writeHelloAck();

        awaitReadyEvent();

        byte[] secondPayload =
                readFrameOfType(secondInput, TYPE_MEDIA_STATE, 4).payload;

        assertArrayEquals(firstPayload, secondPayload);

        ByteBuffer check = ByteBuffer
                .wrap(secondPayload)
                .order(ByteOrder.BIG_ENDIAN);

        // Skip flags + 3 reserved bytes; positionMs is the first uint64.
        check.position(4);
        assertEquals(7_777L, check.getLong());
    }

    @Test
    public void heartbeatsWithPingAndSurvivesPong() throws Exception {
        startConnection();

        InputStream input = fakeCluster.getInputStream();
        OutputStream output = fakeCluster.getOutputStream();

        readFully(input, new byte[EXPECTED_HELLO_FRAME.length]);
        output.write(HELLO_ACK_FRAME);
        output.flush();

        awaitReadyEvent();

        WireFrame ping = readFrameOfType(input, TYPE_PING, 4);

        assertEquals(TYPE_PING, ping.type);
        assertEquals(0, ping.payload.length);

        byte[] pong = {
                0x48, 0x4E, 0x4D, 0x43,
                0x01,
                (byte) 0x82,
                0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        };

        output.write(pong);
        output.flush();

        Thread.sleep(500);
        assertTrue(connection.isReady());
    }

    /*
     * Helpers.
     */

    private void startConnection() throws IOException {
        openServer();
        startConnectionAsync();
        acceptFakeCluster();
    }

    private void openServer() throws IOException {
        server = new ServerSocket(0);
    }

    private void startConnectionAsync() {
        readyEvents = new LinkedBlockingQueue<>();

        connection = new ClusterMediaConnection(
                "127.0.0.1",
                server.getLocalPort(),
                connected -> {
                    if (connected) {
                        readyEvents.offer(Boolean.TRUE);
                    }
                });

        connection.start();
    }

    /** Waits for one ready (connected=true) event with a generous timeout. */
    private void awaitReadyEvent() throws InterruptedException {
        Boolean event = readyEvents.poll(5, TimeUnit.SECONDS);
        assertTrue("timed out waiting for HNMC ready event", event != null);
    }

    private Socket acceptFakeCluster() throws IOException {
        fakeCluster = server.accept();
        fakeCluster.setSoTimeout(15_000);
        return fakeCluster;
    }

    private void writeHelloAck() throws IOException {
        OutputStream output = fakeCluster.getOutputStream();
        output.write(HELLO_ACK_FRAME);
        output.flush();
    }

    private static void readFully(InputStream input, byte[] target)
            throws IOException {

        int offset = 0;

        while (offset < target.length) {
            try {
                int count = input.read(target, offset, target.length - offset);

                if (count < 0) {
                    throw new IOException(
                            "fake cluster stream ended after " + offset);
                }

                offset += count;
            } catch (SocketTimeoutException error) {
                throw new IOException(
                        "timed out waiting for "
                                + (target.length - offset)
                                + " bytes",
                        error);
            }
        }
    }

    private static final class WireFrame {
        final int type;
        final byte[] payload;

        WireFrame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    private static WireFrame readFrame(InputStream input) throws IOException {
        byte[] header = new byte[16];
        readFully(input, header);

        ByteBuffer parsed = ByteBuffer
                .wrap(header)
                .order(ByteOrder.BIG_ENDIAN);

        assertEquals(MAGIC_HNMC, parsed.getInt());
        assertEquals(1, parsed.get() & 0xFF);

        int type = parsed.get() & 0xFF;
        parsed.getShort();
        int payloadLength = parsed.getInt();
        parsed.getInt();

        assertTrue(payloadLength >= 0);

        byte[] payload = new byte[payloadLength];
        readFully(input, payload);

        return new WireFrame(type, payload);
    }

    private static WireFrame readFrameOfType(
            InputStream input,
            int wantedType,
            int maxSkips
    ) throws IOException {

        WireFrame frame = readFrame(input);

        if (frame.type == wantedType) {
            return frame;
        }

        if (maxSkips <= 0) {
            throw new IOException(
                    "expected frame type "
                            + wantedType
                            + " but kept receiving "
                            + frame.type);
        }

        return readFrameOfType(input, wantedType, maxSkips - 1);
    }
}
