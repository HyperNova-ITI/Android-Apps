package com.hypernova.vehiclegateway;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * HyperNova Digital Cluster Navigation protocol.
 *
 * This protocol is intentionally isolated from HNVG/GatewayProtocol.
 *
 * Transport:
 *   Android VehicleGateway -> dedicated TCP socket -> QNX Digital Cluster
 *
 * Wire header (16 bytes, big-endian):
 *   uint32 magic           = "HNCL"
 *   uint8  version         = 1
 *   uint8  type
 *   uint16 flags           = 0
 *   uint32 correlation_id
 *   uint16 payload_length
 *   uint16 reserved        = 0
 *
 * Navigation-state payload:
 *   uint8  active
 *   uint8  maneuver
 *   uint16 speed_limit_kph     (0xFFFF = unknown)
 *   uint32 distance_to_maneuver_m
 *   uint32 remaining_distance_m
 *   uint32 remaining_time_s
 *   uint64 eta_epoch_s
 *   int32  latitude_e7
 *   int32  longitude_e7
 *   uint16 heading_cdeg        (degrees * 100)
 *   uint16 street_name_length
 *   byte[] street_name_utf8
 *   uint16 destination_length
 *   byte[] destination_utf8
 */
final class ClusterNavigationProtocol {
    static final int HEADER_SIZE = 16;
    static final int MAX_PAYLOAD = 4096;
    static final int VERSION = 1;

    static final int TYPE_HELLO = 0x01;
    static final int TYPE_PING = 0x02;

    static final int TYPE_NAVIGATION_STATE = 0x10;
    static final int TYPE_NAVIGATION_CLEAR = 0x11;

    static final int TYPE_HELLO_ACK = 0x81;
    static final int TYPE_PONG = 0x82;

    static final int MANEUVER_UNKNOWN = 0;
    static final int MANEUVER_STRAIGHT = 1;
    static final int MANEUVER_TURN_LEFT = 2;
    static final int MANEUVER_TURN_RIGHT = 3;
    static final int MANEUVER_SLIGHT_LEFT = 4;
    static final int MANEUVER_SLIGHT_RIGHT = 5;
    static final int MANEUVER_SHARP_LEFT = 6;
    static final int MANEUVER_SHARP_RIGHT = 7;
    static final int MANEUVER_UTURN = 8;
    static final int MANEUVER_ROUNDABOUT = 9;
    static final int MANEUVER_ARRIVE = 10;

    static final int UNKNOWN_SPEED_LIMIT = 0xFFFF;

    private static final int MAGIC = 0x484E434C; // HNCL
    private static final int FIXED_NAVIGATION_PAYLOAD_SIZE = 38;
    private static final int MAX_TEXT_BYTES = 1024;

    private ClusterNavigationProtocol() {
    }

    static byte[] encode(int type, long correlationId, byte[] payload) {
        byte[] body = payload == null ? new byte[0] : payload;

        if (body.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload exceeds HNCL limit");
        }

        ByteBuffer out = ByteBuffer
                .allocate(HEADER_SIZE + body.length)
                .order(ByteOrder.BIG_ENDIAN);

        out.putInt(MAGIC);
        out.put((byte) VERSION);
        out.put((byte) type);
        out.putShort((short) 0);
        out.putInt((int) correlationId);
        out.putShort((short) body.length);
        out.putShort((short) 0);
        out.put(body);

        return out.array();
    }

    static DecodeResult tryDecode(byte[] input, int length) throws ProtocolException {
        if (length < HEADER_SIZE) {
            return null;
        }

        ByteBuffer header = ByteBuffer
                .wrap(input, 0, HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN);

        if (header.getInt() != MAGIC) {
            throw new ProtocolException("invalid HNCL magic");
        }

        int version = header.get() & 0xFF;
        int type = header.get() & 0xFF;
        int flags = header.getShort() & 0xFFFF;
        long correlationId = Integer.toUnsignedLong(header.getInt());
        int payloadLength = header.getShort() & 0xFFFF;
        int reserved = header.getShort() & 0xFFFF;

        if (version != VERSION) {
            throw new ProtocolException("unsupported HNCL version");
        }

        if (flags != 0 || reserved != 0) {
            throw new ProtocolException("non-zero reserved fields");
        }

        if (payloadLength > MAX_PAYLOAD) {
            throw new ProtocolException("payload exceeds HNCL limit");
        }

        int total = HEADER_SIZE + payloadLength;

        if (length < total) {
            return null;
        }

        return new DecodeResult(
                new Frame(
                        type,
                        correlationId,
                        Arrays.copyOfRange(input, HEADER_SIZE, total)
                ),
                total
        );
    }

    static byte[] helloPayload() {
        return ByteBuffer
                .allocate(6)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort((short) VERSION)
                .putInt(0x00000001) // navigation presentation capability
                .array();
    }

    static byte[] navigationStatePayload(
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
        validateUnsigned32(distanceToManeuverMeters, "distanceToManeuverMeters");
        validateUnsigned32(remainingDistanceMeters, "remainingDistanceMeters");
        validateUnsigned32(remainingTimeSeconds, "remainingTimeSeconds");

        if (maneuver < 0 || maneuver > 0xFF) {
            throw new IllegalArgumentException("maneuver must fit uint8");
        }

        int encodedSpeedLimit;
        if (speedLimitKph < 0) {
            encodedSpeedLimit = UNKNOWN_SPEED_LIMIT;
        } else if (speedLimitKph > 0xFFFE) {
            throw new IllegalArgumentException("speedLimitKph must fit uint16");
        } else {
            encodedSpeedLimit = speedLimitKph;
        }

        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude out of range");
        }

        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude out of range");
        }

        float normalizedHeading = normalizeHeading(headingDegrees);
        int headingCentiDegrees = Math.round(normalizedHeading * 100.0f);

        byte[] streetBytes = textBytes(streetName);
        byte[] destinationBytes = textBytes(destination);

        int payloadSize = FIXED_NAVIGATION_PAYLOAD_SIZE
                + streetBytes.length
                + destinationBytes.length;

        if (payloadSize > MAX_PAYLOAD) {
            throw new IllegalArgumentException("navigation payload exceeds HNCL limit");
        }

        int latitudeE7 = (int) Math.round(latitude * 10_000_000.0);
        int longitudeE7 = (int) Math.round(longitude * 10_000_000.0);

        ByteBuffer out = ByteBuffer
                .allocate(payloadSize)
                .order(ByteOrder.BIG_ENDIAN);

        out.put((byte) (active ? 1 : 0));
        out.put((byte) maneuver);
        out.putShort((short) encodedSpeedLimit);

        out.putInt((int) distanceToManeuverMeters);
        out.putInt((int) remainingDistanceMeters);
        out.putInt((int) remainingTimeSeconds);

        out.putLong(etaEpochSeconds);

        out.putInt(latitudeE7);
        out.putInt(longitudeE7);

        out.putShort((short) headingCentiDegrees);

        out.putShort((short) streetBytes.length);
        out.put(streetBytes);

        out.putShort((short) destinationBytes.length);
        out.put(destinationBytes);

        return out.array();
    }

    private static byte[] textBytes(String value) {
        String safeValue = value == null ? "" : value;
        byte[] bytes = safeValue.getBytes(StandardCharsets.UTF_8);

        if (bytes.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("navigation text exceeds limit");
        }

        return bytes;
    }

    private static void validateUnsigned32(long value, String name) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException(name + " must fit uint32");
        }
    }

    private static float normalizeHeading(float headingDegrees) {
        if (!Float.isFinite(headingDegrees)) {
            return 0.0f;
        }

        float normalized = headingDegrees % 360.0f;

        if (normalized < 0.0f) {
            normalized += 360.0f;
        }

        return normalized;
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
