package com.hypernova.vehiclegateway;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * HyperNova Digital Cluster Media protocol (HNMC v1).
 *
 * This protocol is intentionally isolated from HNVG/GatewayProtocol and
 * from the HNCL navigation channel.
 *
 * Transport:
 *   Android VehicleGateway -> dedicated TCP socket -> QNX Digital Cluster
 *
 * Wire header (16 bytes, big-endian):
 *   uint32 magic           = "HNMC"
 *   uint8  version         = 1
 *   uint8  type
 *   uint16 reserved        = 0
 *   uint32 payload_length
 *   uint32 correlation_id
 *
 * HELLO payload (exactly 8 bytes):
 *   uint16 version         = 1
 *   uint16 reserved        = 0
 *   uint32 capabilities    = MEDIA_STATE
 *
 * HELLO_ACK payload (exactly 8 bytes):
 *   uint16 version
 *   uint16 reserved
 *   uint32 capabilities    (MEDIA_STATE required, ARTWORK never required)
 *
 * MEDIA_STATE payload:
 *   uint8  flags           (bit0 has_media, bit1 playing)
 *   uint8  reserved[3]     = 0
 *   uint64 position_ms
 *   uint64 duration_ms     (0 is valid)
 *   uint16 media_id_length (UTF-8 bytes)
 *   uint16 title_length
 *   uint16 artist_length
 *   uint16 album_length
 *   byte[] text_utf8       (concatenated in the order above)
 */
final class ClusterMediaProtocol {
    static final int HEADER_SIZE = 16;
    static final int MAX_PAYLOAD = 512 * 1024;
    static final int VERSION = 1;

    static final int TYPE_HELLO = 0x01;
    static final int TYPE_PING = 0x02;

    static final int TYPE_MEDIA_STATE = 0x10;
    static final int TYPE_MEDIA_CLEAR = 0x11;

    static final int TYPE_HELLO_ACK = 0x81;
    static final int TYPE_PONG = 0x82;

    static final int CAPABILITY_MEDIA_STATE = 0x00000001;
    static final int CAPABILITY_ARTWORK = 0x00000002;

    static final int FLAG_HAS_MEDIA = 0x01;
    static final int FLAG_PLAYING = 0x02;

    static final int MAX_TEXT_BYTES = 2048;

    private static final int MAGIC = 0x484E4D43; // HNMC
    private static final int FIXED_MEDIA_PAYLOAD_SIZE =
            1          // flags
            + 3        // reserved
            + 8 + 8    // position_ms + duration_ms
            + 2 * 4;   // four uint16 UTF-8 lengths
    private static final int HELLO_PAYLOAD_SIZE = 8;
    private static final int HELLO_ACK_PAYLOAD_SIZE = 8;

    private ClusterMediaProtocol() {
    }

    static byte[] encode(int type, long correlationId, byte[] payload) {
        byte[] body = payload == null ? new byte[0] : payload;

        if (body.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload exceeds HNMC limit");
        }

        ByteBuffer out = ByteBuffer
                .allocate(HEADER_SIZE + body.length)
                .order(ByteOrder.BIG_ENDIAN);

        out.putInt(MAGIC);
        out.put((byte) VERSION);
        out.put((byte) type);
        out.putShort((short) 0);
        out.putInt(body.length);
        out.putInt((int) correlationId);

        out.put(body);

        return out.array();
    }

    /**
     * Decodes one frame from the front of the input buffer.
     *
     * Returns null when more bytes are needed. Throws ProtocolException on
     * wrong magic, unsupported version, non-zero reserved field, or an
     * oversized payload declaration.
     */
    static DecodeResult tryDecode(byte[] input, int length) throws ProtocolException {
        if (length < HEADER_SIZE) {
            return null;
        }

        ByteBuffer header = ByteBuffer
                .wrap(input, 0, HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN);

        if (header.getInt() != MAGIC) {
            throw new ProtocolException("invalid HNMC magic");
        }

        int version = header.get() & 0xFF;
        int type = header.get() & 0xFF;
        int reserved = header.getShort() & 0xFFFF;
        long payloadLength = Integer.toUnsignedLong(header.getInt());
        long correlationId = Integer.toUnsignedLong(header.getInt());

        if (version != VERSION) {
            throw new ProtocolException("unsupported HNMC version");
        }

        if (reserved != 0) {
            throw new ProtocolException("non-zero reserved field");
        }

        if (payloadLength > MAX_PAYLOAD) {
            throw new ProtocolException("payload exceeds HNMC limit");
        }

        long total = HEADER_SIZE + payloadLength;

        if (length < total) {
            return null;
        }

        return new DecodeResult(
                new Frame(
                        type,
                        correlationId,
                        Arrays.copyOfRange(input, HEADER_SIZE, (int) total)
                ),
                (int) total
        );
    }

    static byte[] helloPayload() {
        return ByteBuffer
                .allocate(HELLO_PAYLOAD_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort((short) VERSION)
                .putShort((short) 0)
                .putInt(CAPABILITY_MEDIA_STATE)
                .array();
    }

    /**
     * Validates a HELLO_ACK payload from QNX.
     *
     * MEDIA_STATE must be advertised; ARTWORK is never required.
     */
    static void validateHelloAck(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length != HELLO_ACK_PAYLOAD_SIZE) {
            throw new ProtocolException(
                    "invalid HELLO_ACK payload length: expected "
                            + HELLO_ACK_PAYLOAD_SIZE
                            + ", received "
                            + (payload == null ? 0 : payload.length)
            );
        }

        ByteBuffer in = ByteBuffer
                .wrap(payload)
                .order(ByteOrder.BIG_ENDIAN);

        int version = in.getShort() & 0xFFFF;
        int reserved = in.getShort() & 0xFFFF;
        int capabilities = in.getInt();

        if (version != VERSION) {
            throw new ProtocolException(
                    "QNX cluster media API version mismatch"
            );
        }

        if (reserved != 0) {
            throw new ProtocolException(
                    "non-zero reserved field in HELLO_ACK"
            );
        }

        if ((capabilities & CAPABILITY_MEDIA_STATE) == 0) {
            throw new ProtocolException(
                    "QNX cluster does not advertise media state capability"
            );
        }
    }

    static byte[] mediaStatePayload(
            boolean hasMedia,
            boolean playing,
            long positionMs,
            long durationMs,
            String mediaId,
            String title,
            String artist,
            String album
    ) {
        if (positionMs < 0) {
            throw new IllegalArgumentException("positionMs must not be negative");
        }

        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }

        byte[] idBytes = textBytes(mediaId);
        byte[] titleBytes = textBytes(title);
        byte[] artistBytes = textBytes(artist);
        byte[] albumBytes = textBytes(album);

        int payloadSize = FIXED_MEDIA_PAYLOAD_SIZE
                + idBytes.length
                + titleBytes.length
                + artistBytes.length
                + albumBytes.length;

        if (payloadSize > MAX_PAYLOAD) {
            throw new IllegalArgumentException("media payload exceeds HNMC limit");
        }

        int flags = 0;

        if (hasMedia) {
            flags |= FLAG_HAS_MEDIA;
        }

        if (playing) {
            flags |= FLAG_PLAYING;
        }

        ByteBuffer out = ByteBuffer
                .allocate(payloadSize)
                .order(ByteOrder.BIG_ENDIAN);

        out.put((byte) flags);
        out.put((byte) 0);
        out.put((byte) 0);
        out.put((byte) 0);

        out.putLong(positionMs);
        out.putLong(durationMs);

        out.putShort((short) idBytes.length);
        out.putShort((short) titleBytes.length);
        out.putShort((short) artistBytes.length);
        out.putShort((short) albumBytes.length);

        out.put(idBytes);
        out.put(titleBytes);
        out.put(artistBytes);
        out.put(albumBytes);

        return out.array();
    }

    private static byte[] textBytes(String value) {
        String safeValue = value == null ? "" : value;
        byte[] bytes = safeValue.getBytes(StandardCharsets.UTF_8);

        if (bytes.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("media text exceeds UTF-8 limit");
        }

        return bytes;
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
