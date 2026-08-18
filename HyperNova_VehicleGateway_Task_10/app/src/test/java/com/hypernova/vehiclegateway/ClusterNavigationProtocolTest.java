package com.hypernova.vehiclegateway;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ClusterNavigationProtocolTest {

    @Test
    public void fragmentedFrameWaitsForAllBytes() throws Exception {
        byte[] payload = ClusterNavigationProtocol.navigationStatePayload(
                true,
                ClusterNavigationProtocol.MANEUVER_TURN_RIGHT,
                80,
                300,
                8200,
                720,
                1_800_000_000L,
                30.0712345,
                31.0176543,
                95.5f,
                "Sheikh Zayed Road",
                "Smart Village"
        );

        byte[] encoded = ClusterNavigationProtocol.encode(
                ClusterNavigationProtocol.TYPE_NAVIGATION_STATE,
                42,
                payload
        );

        assertNull(
                ClusterNavigationProtocol.tryDecode(
                        encoded,
                        ClusterNavigationProtocol.HEADER_SIZE + 1
                )
        );

        ClusterNavigationProtocol.DecodeResult result =
                ClusterNavigationProtocol.tryDecode(encoded, encoded.length);

        assertEquals(encoded.length, result.consumed);
        assertEquals(
                ClusterNavigationProtocol.TYPE_NAVIGATION_STATE,
                result.frame.type
        );
        assertEquals(42, result.frame.correlationId);
        assertArrayEquals(payload, result.frame.payload);
    }

    @Test
    public void mergedFramesDecodeOneAtATime() throws Exception {
        byte[] first = ClusterNavigationProtocol.encode(
                ClusterNavigationProtocol.TYPE_PING,
                1,
                null
        );

        byte[] second = ClusterNavigationProtocol.encode(
                ClusterNavigationProtocol.TYPE_NAVIGATION_CLEAR,
                2,
                null
        );

        byte[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);

        ClusterNavigationProtocol.DecodeResult decoded =
                ClusterNavigationProtocol.tryDecode(both, both.length);

        assertEquals(first.length, decoded.consumed);
        assertEquals(ClusterNavigationProtocol.TYPE_PING, decoded.frame.type);
        assertEquals(1, decoded.frame.correlationId);
    }

    @Test
    public void navigationPayloadContainsExpectedPresentationState() {
        byte[] payload = ClusterNavigationProtocol.navigationStatePayload(
                true,
                ClusterNavigationProtocol.MANEUVER_TURN_RIGHT,
                80,
                300,
                8200,
                720,
                1_800_000_000L,
                30.0712345,
                31.0176543,
                95.5f,
                "Sheikh Zayed Road",
                "Smart Village"
        );

        ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);

        assertEquals(1, in.get() & 0xFF);
        assertEquals(
                ClusterNavigationProtocol.MANEUVER_TURN_RIGHT,
                in.get() & 0xFF
        );
        assertEquals(80, in.getShort() & 0xFFFF);

        assertEquals(300L, Integer.toUnsignedLong(in.getInt()));
        assertEquals(8200L, Integer.toUnsignedLong(in.getInt()));
        assertEquals(720L, Integer.toUnsignedLong(in.getInt()));

        assertEquals(1_800_000_000L, in.getLong());

        assertEquals(300_712_345, in.getInt());
        assertEquals(310_176_543, in.getInt());

        assertEquals(9550, in.getShort() & 0xFFFF);

        int streetLength = in.getShort() & 0xFFFF;
        byte[] streetBytes = new byte[streetLength];
        in.get(streetBytes);

        int destinationLength = in.getShort() & 0xFFFF;
        byte[] destinationBytes = new byte[destinationLength];
        in.get(destinationBytes);

        assertEquals(
                "Sheikh Zayed Road",
                new String(streetBytes, StandardCharsets.UTF_8)
        );
        assertEquals(
                "Smart Village",
                new String(destinationBytes, StandardCharsets.UTF_8)
        );

        assertEquals(0, in.remaining());
    }

    @Test
    public void negativeHeadingIsNormalized() {
        byte[] payload = ClusterNavigationProtocol.navigationStatePayload(
                true,
                ClusterNavigationProtocol.MANEUVER_STRAIGHT,
                -1,
                0,
                0,
                0,
                0,
                0.0,
                0.0,
                -90.0f,
                "",
                ""
        );

        ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);

        in.position(2);

        assertEquals(
                ClusterNavigationProtocol.UNKNOWN_SPEED_LIMIT,
                in.getShort() & 0xFFFF
        );

        in.position(32);

        assertEquals(27000, in.getShort() & 0xFFFF);
    }

    @Test(expected = ClusterNavigationProtocol.ProtocolException.class)
    public void badMagicIsRejected() throws Exception {
        byte[] encoded = ClusterNavigationProtocol.encode(
                ClusterNavigationProtocol.TYPE_PING,
                0,
                null
        );

        encoded[0] = 0;

        ClusterNavigationProtocol.tryDecode(encoded, encoded.length);
    }
}
