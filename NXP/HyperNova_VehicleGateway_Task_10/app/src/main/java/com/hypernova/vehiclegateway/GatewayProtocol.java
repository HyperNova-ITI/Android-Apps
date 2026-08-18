package com.hypernova.vehiclegateway;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Strict HNVG v1 encoder/stream decoder. */
final class GatewayProtocol {
    static final int HEADER_SIZE = 16;
    static final int MAX_PAYLOAD = 512;
    static final int VERSION = 1;

    static final int TYPE_HELLO = 0x01;
    static final int TYPE_PING = 0x02;
    static final int TYPE_SET_HVAC = 0x10;
    static final int TYPE_GET_STATE = 0x20;
    static final int TYPE_HELLO_ACK = 0x81;
    static final int TYPE_PONG = 0x82;
    static final int TYPE_COMMAND_RESULT = 0x90;
    static final int TYPE_VEHICLE_STATE = 0xA0;
    static final int TYPE_FAULT_EVENT = 0xA1;

    private static final int MAGIC = 0x484E5647; // HNVG

    private GatewayProtocol() {
    }

    static byte[] encode(int type, long correlationId, byte[] payload) {
        byte[] body = payload == null ? new byte[0] : payload;
        if (body.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload exceeds HNVG limit");
        }
        ByteBuffer out = ByteBuffer.allocate(HEADER_SIZE + body.length).order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC);
        out.put((byte) VERSION);
        out.put((byte) type);
        out.putShort((short) 0); // flags
        out.putInt((int) correlationId);
        out.putShort((short) body.length);
        out.putShort((short) 0); // reserved
        out.put(body);
        return out.array();
    }

    static DecodeResult tryDecode(byte[] input, int length) throws ProtocolException {
        if (length < HEADER_SIZE) return null;
        ByteBuffer header = ByteBuffer.wrap(input, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        if (header.getInt() != MAGIC) throw new ProtocolException("invalid magic");
        int version = header.get() & 0xFF;
        int type = header.get() & 0xFF;
        int flags = header.getShort() & 0xFFFF;
        long correlationId = Integer.toUnsignedLong(header.getInt());
        int payloadLength = header.getShort() & 0xFFFF;
        int reserved = header.getShort() & 0xFFFF;
        if (version != VERSION) throw new ProtocolException("unsupported version");
        if (flags != 0 || reserved != 0) throw new ProtocolException("non-zero reserved fields");
        if (payloadLength > MAX_PAYLOAD) throw new ProtocolException("payload exceeds limit");
        int total = HEADER_SIZE + payloadLength;
        if (length < total) return null;
        return new DecodeResult(
                new Frame(type, correlationId, Arrays.copyOfRange(input, HEADER_SIZE, total)),
                total
        );
    }

    static byte[] helloPayload() {
        return ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) VERSION)
                .putInt(0x00000007) // climate + telemetry + DTC events
                .array();
    }

    static byte[] climatePayload(int target, int fan, int zone, int caller) {
        return new byte[]{(byte) target, (byte) fan, (byte) zone, (byte) caller};
    }

    static final class Frame {
        final int type;
        final long correlationId;
        final byte[] payload;

        Frame(int type, long correlationId, byte[] payload) {
            this.type = type;
            this.correlationId = correlationId;
            this.payload = payload;
        }
    }

    static final class DecodeResult {
        final Frame frame;
        final int consumed;

        DecodeResult(Frame frame, int consumed) {
            this.frame = frame;
            this.consumed = consumed;
        }
    }

    static final class ProtocolException extends Exception {
        ProtocolException(String message) {
            super(message);
        }
    }
}
