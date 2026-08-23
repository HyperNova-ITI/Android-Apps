package com.hypernova.vehiclegateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hypernova.contracts.media.MediaContract;
import com.hypernova.contracts.media.MediaPlaybackSnapshot;

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
 * Focused integration-style unit tests for the Worker C bridge mapping:
 * MediaPlaybackSnapshot -> HNMC wire frames via ClusterMediaConnection.
 *
 * They drive a real ClusterMediaConnection against an in-process fake QNX
 * media endpoint (mirroring ClusterMediaConnectionTest) so the exact frames
 * produced by bridge decisions are asserted on the wire.
 */
public final class MediaClusterBridgeTest {

    private static final int MAGIC_HNMC = 0x484E4D43;

    private static final int TYPE_MEDIA_STATE = 0x10;
    private static final int TYPE_MEDIA_CLEAR = 0x11;

    /** Exact HELLO wire frame expected from the gateway. */
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
    public void publishesActiveSnapshotAsMediaStateFrame() throws Exception {
        startBridge();

        MediaClusterBridge.applySnapshot(
                connection,
                new MediaPlaybackSnapshot(
                        true,
                        true,
                        MediaContract.PLAYBACK_STATE_READY,
                        45_000L,
                        200_000L,
                        "media-77",
                        "Song Title",
                        "Artist Name",
                        "Album Name",
                        "content://secret/artwork"));

        completeHandshake();

        InputStream input = fakeCluster.getInputStream();

        WireFrame state = readFrameOfType(input, TYPE_MEDIA_STATE);

        ByteBuffer payload = ByteBuffer
                .wrap(state.payload)
                .order(ByteOrder.BIG_ENDIAN);

        assertEquals(0x03, payload.get() & 0xFF); // has_media + playing
        assertEquals(0, payload.get() & 0xFF);
        assertEquals(0, payload.get() & 0xFF);
        assertEquals(0, payload.get() & 0xFF);
        assertEquals(45_000L, payload.getLong());
        assertEquals(200_000L, payload.getLong());

        String[] texts = readTexts(payload);

        assertEquals("media-77", texts[0]);
        assertEquals("Song Title", texts[1]);
        assertEquals("Artist Name", texts[2]);
        assertEquals("Album Name", texts[3]);

        // artworkUri must never be forwarded to HNMC.
        assertFalse(
                new String(state.payload, StandardCharsets.UTF_8)
                        .contains("secret"));
    }

    @Test
    public void mapsNoMediaSnapshotToMediaClearFrame() throws Exception {
        startBridge();
        completeHandshake();

        MediaClusterBridge.applySnapshot(
                connection,
                new MediaPlaybackSnapshot(
                        false,
                        false,
                        MediaContract.PLAYBACK_STATE_IDLE,
                        0L,
                        0L,
                        "",
                        "",
                        "",
                        "",
                        ""));

        WireFrame clear =
                readFrameOfType(fakeCluster.getInputStream(), TYPE_MEDIA_CLEAR);

        assertEquals(TYPE_MEDIA_CLEAR, clear.type);
        assertEquals(0, clear.payload.length);
    }

    @Test
    public void trimsOversizedTextWithinProtocolLimits() throws Exception {
        startBridge();

        // 5000 ASCII bytes and 3000 three-byte chars both exceed the HNMC
        // per-field limit of 2048 UTF-8 bytes; the bridge must trim them so
        // publication cannot be rejected.
        MediaClusterBridge.applySnapshot(
                connection,
                new MediaPlaybackSnapshot(
                        true,
                        true,
                        MediaContract.PLAYBACK_STATE_READY,
                        1_000L,
                        2_000L,
                        repeat('a', 5000),
                        repeat('*', 5000),
                        repeat('\u20AC', 3000),
                        "Album",
                        "content://secret/artwork"));

        completeHandshake();

        WireFrame state =
                readFrameOfType(fakeCluster.getInputStream(), TYPE_MEDIA_STATE);

        ByteBuffer payload = ByteBuffer
                .wrap(state.payload)
                .order(ByteOrder.BIG_ENDIAN);

        payload.position(4); // flags + reserved
        payload.getLong();   // positionMs
        payload.getLong();   // durationMs

        int idLength = payload.getShort() & 0xFFFF;
        int titleLength = payload.getShort() & 0xFFFF;
        int artistLength = payload.getShort() & 0xFFFF;
        int albumLength = payload.getShort() & 0xFFFF;

        assertTrue(idLength <= 2048);
        assertTrue(titleLength <= 2048);
        assertTrue(artistLength <= 2048);
        assertEquals("Album".getBytes(
                StandardCharsets.UTF_8).length, albumLength);

        assertFalse(
                new String(state.payload, StandardCharsets.UTF_8)
                        .contains("secret"));
    }

    @Test
    public void clampsNegativeProgressValuesBeforePublication() throws Exception {
        assertEquals(0L, MediaClusterBridge.clampNonNegative(-1L));
        assertEquals(0L, MediaClusterBridge.clampNonNegative(Long.MIN_VALUE));
        assertEquals(42L, MediaClusterBridge.clampNonNegative(42L));
    }

    /*
     * Helpers.
     */

    private void startBridge() throws IOException {
        server = new ServerSocket(0);

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

        fakeCluster = server.accept();
        fakeCluster.setSoTimeout(15_000);

        byte[] hello = new byte[EXPECTED_HELLO_FRAME.length];
        readFully(fakeCluster.getInputStream(), hello);
    }

    private void completeHandshake() throws InterruptedException, IOException {
        OutputStream output = fakeCluster.getOutputStream();
        output.write(HELLO_ACK_FRAME);
        output.flush();

        Boolean event = readyEvents.poll(5, TimeUnit.SECONDS);
        assertTrue("timed out waiting for HNMC ready event", event != null);
        assertTrue(connection.isReady());
    }

    private static String repeat(char character, int count) {
        StringBuilder builder = new StringBuilder(count);

        for (int i = 0; i < count; i++) {
            builder.append(character);
        }

        return builder.toString();
    }

    private static String[] readTexts(ByteBuffer payload) {
        int idLength = payload.getShort() & 0xFFFF;
        int titleLength = payload.getShort() & 0xFFFF;
        int artistLength = payload.getShort() & 0xFFFF;
        int albumLength = payload.getShort() & 0xFFFF;

        return new String[] {
                readUtf(payload, idLength),
                readUtf(payload, titleLength),
                readUtf(payload, artistLength),
                readUtf(payload, albumLength)
        };
    }

    private static String readUtf(ByteBuffer payload, int length) {
        byte[] bytes = new byte[length];
        payload.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
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
            int wantedType
    ) throws IOException {

        WireFrame frame = readFrame(input);

        if (frame.type == wantedType) {
            return frame;
        }

        // Skip unrelated frames such as heartbeats.
        return readFrameOfType(input, wantedType);
    }
}
