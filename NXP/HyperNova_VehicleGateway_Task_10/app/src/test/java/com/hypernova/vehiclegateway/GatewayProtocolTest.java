package com.hypernova.vehiclegateway;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;

public final class GatewayProtocolTest {
    @Test
    public void fragmentedFrameWaitsForAllBytes() throws Exception {
        byte[] encoded = GatewayProtocol.encode(
                GatewayProtocol.TYPE_SET_HVAC,
                42,
                GatewayProtocol.climatePayload(22, 3, 0, 1)
        );
        assertNull(GatewayProtocol.tryDecode(encoded, GatewayProtocol.HEADER_SIZE + 1));

        GatewayProtocol.DecodeResult result = GatewayProtocol.tryDecode(encoded, encoded.length);
        assertEquals(encoded.length, result.consumed);
        assertEquals(GatewayProtocol.TYPE_SET_HVAC, result.frame.type);
        assertEquals(42, result.frame.correlationId);
        assertArrayEquals(new byte[]{22, 3, 0, 1}, result.frame.payload);
    }

    @Test
    public void mergedFramesDecodeOneAtATime() throws Exception {
        byte[] first = GatewayProtocol.encode(GatewayProtocol.TYPE_PING, 0, null);
        byte[] second = GatewayProtocol.encode(GatewayProtocol.TYPE_GET_STATE, 0, null);
        byte[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);

        GatewayProtocol.DecodeResult decoded = GatewayProtocol.tryDecode(both, both.length);
        assertEquals(first.length, decoded.consumed);
        assertEquals(GatewayProtocol.TYPE_PING, decoded.frame.type);
    }

    @Test(expected = GatewayProtocol.ProtocolException.class)
    public void badMagicIsRejected() throws Exception {
        byte[] encoded = GatewayProtocol.encode(GatewayProtocol.TYPE_PING, 0, null);
        encoded[0] = 0;
        GatewayProtocol.tryDecode(encoded, encoded.length);
    }
}
