package com.hypernova.vehiclegateway;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ClusterMediaProtocolTest {

    private static final int MAGIC_HNMC = 0x484E4D43;

    @Test
    public void pingFrameHasExactHeaderBytes() throws Exception {
        byte[] encoded = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_PING,
                0x11223344L,
                null
        );

        byte[] expected = {
                0x48, 0x4E, 0x4D, 0x43, // magic "HNMC"
                0x01,                   // version
                0x02,                   // type PING
                0x00, 0x00,             // reserved
                0x00, 0x00, 0x00, 0x00, // payload length 0
                0x11, 0x22, 0x33, 0x44  // correlation id
        };

        assertEquals(ClusterMediaProtocol.HEADER_SIZE, encoded.length);
        assertArrayEquals(expected, encoded);
    }

    @Test
    public void payloadLengthIsEncodedBigEndian() {
        byte[] body = new byte[257]; // 0x0101

        byte[] encoded = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_MEDIA_CLEAR,
                0,
                body
        );

        // Payload length lives at bytes 8..11, correlation id at 12..15.
        assertEquals(0x00, encoded[8]);
        assertEquals(0x00, encoded[9]);
        assertEquals(0x01, encoded[10]);
        assertEquals(0x01, encoded[11]);
        assertEquals(0x00, encoded[12]);
        assertEquals(0x00, encoded[13]);
        assertEquals(0x00, encoded[14]);
        assertEquals(0x00, encoded[15]);
        assertEquals(ClusterMediaProtocol.HEADER_SIZE + 257, encoded.length);
    }

    @Test
    public void correlationIdSurvivesUnsignedRoundTrip() throws Exception {
        byte[] encoded = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_PING,
                0xFFFF_FFFFL,
                null
        );

        ClusterMediaProtocol.DecodeResult result =
                ClusterMediaProtocol.tryDecode(encoded, encoded.length);

        assertEquals(4_294_967_295L, result.frame.correlationId);
    }

    @Test
    public void helloFrameHasExactWireBytes() throws Exception {
        byte[] encoded = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_HELLO,
                0,
                ClusterMediaProtocol.helloPayload()
        );

        byte[] expected = {
                0x48, 0x4E, 0x4D, 0x43, // magic "HNMC"
                0x01,                   // version
                0x01,                   // type HELLO
                0x00, 0x00,             // reserved
                0x00, 0x00, 0x00, 0x08, // payload length 8
                0x00, 0x00, 0x00, 0x00, // correlation id
                0x00, 0x01,             // protocol version 1
                0x00, 0x00,             // reserved
                0x00, 0x00, 0x00, 0x01  // capability MEDIA_STATE only
        };

        assertArrayEquals(expected, encoded);

        byte[] payload = ClusterMediaProtocol.helloPayload();

        assertEquals(8, payload.length);
        assertEquals(0x00, payload[0] & 0xFF); // version high byte
        assertEquals(0x01, payload[1] & 0xFF); // version low byte
        assertEquals(0, payload[2] & 0xFF);
        assertEquals(0, payload[3] & 0xFF);
        assertEquals(
                ClusterMediaProtocol.CAPABILITY_MEDIA_STATE,
                ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getInt(4)
        );
    }

    @Test
    public void mediaStateFrameHasExactWireBytes() throws Exception {
        byte[] payload = ClusterMediaProtocol.mediaStatePayload(
                false,
                true,
                1_000L,
                0L,
                "",
                "AB",
                "",
                ""
        );

        byte[] expectedPayload = {
                0x02,                   // flags: playing, not hasMedia
                0x00, 0x00, 0x00,       // reserved
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, (byte) 0xE8, // position 1000
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // duration 0
                0x00, 0x00,             // mediaId length 0
                0x00, 0x02,             // title length 2
                0x00, 0x00,             // artist length 0
                0x00, 0x00,             // album length 0
                0x41, 0x42              // "AB"
        };

        assertArrayEquals(expectedPayload, payload);

        byte[] frame = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_MEDIA_STATE,
                0,
                payload
        );

        byte[] expectedHeader = {
                0x48, 0x4E, 0x4D, 0x43,
                0x01,
                0x10,
                0x00, 0x00,
                0x00, 0x00, 0x00, 0x1E, // payload length 30
                0x00, 0x00, 0x00, 0x00
        };

        assertArrayEquals(expectedHeader, Arrays.copyOfRange(frame, 0, 16));

        ClusterMediaProtocol.DecodeResult result =
                ClusterMediaProtocol.tryDecode(frame, frame.length);

        assertEquals(ClusterMediaProtocol.TYPE_MEDIA_STATE, result.frame.type);
        assertArrayEquals(payload, result.frame.payload);
    }

    @Test
    public void mediaStateUsesUtf8ByteLengthsForNonAsciiText() {
        // 5 chars -> 10 UTF-8 bytes.
        String arabicTitle = "أغنية";
        // One char outside the BMP -> 4 UTF-8 bytes.
        String emojiAlbum = "\uD83C\uDFB5";

        byte[] payload = ClusterMediaProtocol.mediaStatePayload(
                true,
                true,
                86_400_000L,
                3_600_000L,
                "m1",
                arabicTitle,
                "",
                emojiAlbum
        );

        ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);

        int flags = in.get() & 0xFF;
        assertEquals(ClusterMediaProtocol.FLAG_HAS_MEDIA
                | ClusterMediaProtocol.FLAG_PLAYING, flags);
        assertEquals(0, in.get() & 0xFF);
        assertEquals(0, in.get() & 0xFF);
        assertEquals(0, in.get() & 0xFF);

        assertEquals(86_400_000L, in.getLong());
        assertEquals(3_600_000L, in.getLong());

        int idLength = in.getShort() & 0xFFFF;
        int titleLength = in.getShort() & 0xFFFF;
        int artistLength = in.getShort() & 0xFFFF;
        int albumLength = in.getShort() & 0xFFFF;

        assertEquals("m1".getBytes(StandardCharsets.UTF_8).length, idLength);
        assertEquals(arabicTitle.getBytes(StandardCharsets.UTF_8).length, titleLength);
        assertEquals(0, artistLength);
        assertEquals(emojiAlbum.getBytes(StandardCharsets.UTF_8).length, albumLength);
        assertEquals(10, titleLength); // byte length, not char length
        assertEquals(4, albumLength);

        byte[] text = new byte[in.remaining()];
        in.get(text);

        byte[] expectedText = concat(
                concat("m1".getBytes(StandardCharsets.UTF_8),
                        arabicTitle.getBytes(StandardCharsets.UTF_8)),
                concat(new byte[0],
                        concat(emojiAlbum.getBytes(StandardCharsets.UTF_8),
                                new byte[0]))
        );

        assertArrayEquals(expectedText, text);
        assertEquals(
                "m1" + arabicTitle + "" + emojiAlbum,
                new String(text, StandardCharsets.UTF_8)
        );
    }

    @Test
    public void textBoundaryAt2048BytesAcceptedAndBeyondRejected() {
        // 1024 chars of a two-byte code point -> exactly 2048 UTF-8 bytes.
        String twoByteBoundary = new String(
                new char[1024]).replace("\0", "\u0646");

        byte[] ok = ClusterMediaProtocol.mediaStatePayload(
                true, false, 0, 0, twoByteBoundary, "", "", "");
        assertEquals(28 + 2048, ok.length);

        String asciiBoundary = repeat('a', 2048);
        ClusterMediaProtocol.mediaStatePayload(
                true, false, 0, 0, asciiBoundary, "", "", "");

        try {
            ClusterMediaProtocol.mediaStatePayload(
                    true, false, 0, 0, asciiBoundary + "a", "", "", "");
            fail("2049 ASCII bytes must be rejected");
        } catch (IllegalArgumentException expected) {
            // Ignored.
        }

        try {
            ClusterMediaProtocol.mediaStatePayload(
                    true, false, 0, 0, twoByteBoundary + "\u0646", "", "", "");
            fail("2050 UTF-8 bytes must be rejected");
        } catch (IllegalArgumentException expected) {
            // Ignored.
        }
    }

    @Test
    public void negativePositionAndDurationAreRejected() {
        try {
            ClusterMediaProtocol.mediaStatePayload(
                    true, true, -1, 0, "", "", "", "");
            fail("negative position must be rejected");
        } catch (IllegalArgumentException expected) {
            // Ignored.
        }

        try {
            ClusterMediaProtocol.mediaStatePayload(
                    true, true, 0, -1, "", "", "", "");
            fail("negative duration must be rejected");
        } catch (IllegalArgumentException expected) {
            // Ignored.
        }
    }

    @Test
    public void encodeRejectsPayloadAboveLimitAndAcceptsExactLimit() {
        try {
            ClusterMediaProtocol.encode(
                    ClusterMediaProtocol.TYPE_MEDIA_STATE,
                    0,
                    new byte[ClusterMediaProtocol.MAX_PAYLOAD + 1]
            );
            fail("oversized payload must be rejected");
        } catch (IllegalArgumentException expected) {
            // Ignored.
        }

        byte[] exact = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_MEDIA_STATE,
                0,
                new byte[ClusterMediaProtocol.MAX_PAYLOAD]
        );
        assertEquals(ClusterMediaProtocol.HEADER_SIZE
                + ClusterMediaProtocol.MAX_PAYLOAD, exact.length);
    }

    @Test
    public void fragmentedFramesWaitForAllBytes() throws Exception {
        byte[] payload = ClusterMediaProtocol.mediaStatePayload(
                true, true, 250, 180_000, "id-9", "T", "A", "Al");

        byte[] encoded = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_MEDIA_STATE,
                77,
                payload
        );

        assertNull(ClusterMediaProtocol.tryDecode(encoded, 10));
        assertNull(
                ClusterMediaProtocol.tryDecode(
                        encoded, ClusterMediaProtocol.HEADER_SIZE));
        assertNull(
                ClusterMediaProtocol.tryDecode(
                        encoded, encoded.length - 1));

        ClusterMediaProtocol.DecodeResult result =
                ClusterMediaProtocol.tryDecode(encoded, encoded.length);

        assertEquals(encoded.length, result.consumed);
        assertEquals(77, result.frame.correlationId);
        assertArrayEquals(payload, result.frame.payload);
    }

    @Test
    public void truncatedDeclaredLengthWaitsForMoreBytes() throws Exception {
        byte[] encoded = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_MEDIA_STATE,
                0,
                new byte[64]
        );

        byte[] shortByFour = Arrays.copyOfRange(encoded, 0, encoded.length - 4);

        ClusterMediaProtocol.DecodeResult result =
                ClusterMediaProtocol.tryDecode(shortByFour, shortByFour.length);

        assertNull(result);
    }

    @Test
    public void mergedFramesDecodeOneAtATime() throws Exception {
        byte[] first = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_PING, 1, null);
        byte[] second = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_MEDIA_CLEAR, 2, null);

        byte[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);

        ClusterMediaProtocol.DecodeResult decoded =
                ClusterMediaProtocol.tryDecode(both, both.length);

        assertEquals(ClusterMediaProtocol.HEADER_SIZE, decoded.consumed);
        assertEquals(ClusterMediaProtocol.TYPE_PING, decoded.frame.type);
        assertEquals(1, decoded.frame.correlationId);
    }

    @Test(expected = ClusterMediaProtocol.ProtocolException.class)
    public void badMagicIsRejected() throws Exception {
        byte[] encoded = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_PING, 0, null);
        encoded[0] = 0x58;

        ClusterMediaProtocol.tryDecode(encoded, encoded.length);
    }

    @Test(expected = ClusterMediaProtocol.ProtocolException.class)
    public void wrongVersionIsRejected() throws Exception {
        byte[] encoded = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_PING, 0, null);
        encoded[4] = 0x02;

        ClusterMediaProtocol.tryDecode(encoded, encoded.length);
    }

    @Test(expected = ClusterMediaProtocol.ProtocolException.class)
    public void nonzeroReservedFieldIsRejected() throws Exception {
        byte[] encoded = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_PING, 0, null);
        encoded[6] = 0x01;

        ClusterMediaProtocol.tryDecode(encoded, encoded.length);
    }

    @Test
    public void oversizedDeclaredPayloadIsRejected() {
        byte[] encoded = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_PING, 0, null);

        // 0x00080001 = 524289 > 512 KiB limit; length field is bytes 8..11.
        encoded[8] = 0x00;
        encoded[9] = 0x08;
        encoded[10] = 0x00;
        encoded[11] = 0x01;

        try {
            ClusterMediaProtocol.tryDecode(encoded, encoded.length);
            fail("oversized declared payload must be rejected");
        } catch (ClusterMediaProtocol.ProtocolException expected) {
            // Ignored.
        }
    }

    @Test
    public void mediaClearCarriesZeroPayload() throws Exception {
        byte[] encoded = ClusterMediaProtocol.encode(
                ClusterMediaProtocol.TYPE_MEDIA_CLEAR, 7, null);

        assertEquals(ClusterMediaProtocol.HEADER_SIZE, encoded.length);
        assertEquals(0x11, encoded[5] & 0xFF);
        // Zero-payload declaration at bytes 8..11.
        assertEquals(0, encoded[8]);
        assertEquals(0, encoded[9]);
        assertEquals(0, encoded[10]);
        assertEquals(0, encoded[11]);
        // Correlation id 7 in big-endian at bytes 12..15.
        assertEquals(0x00, encoded[12]);
        assertEquals(0x00, encoded[13]);
        assertEquals(0x00, encoded[14]);
        assertEquals(0x07, encoded[15]);

        ClusterMediaProtocol.DecodeResult result =
                ClusterMediaProtocol.tryDecode(encoded, encoded.length);

        assertEquals(ClusterMediaProtocol.TYPE_MEDIA_CLEAR, result.frame.type);
        assertEquals(7, result.frame.correlationId);
        assertEquals(0, result.frame.payload.length);
        assertEquals(ClusterMediaProtocol.HEADER_SIZE, result.consumed);
    }

    @Test
    public void helloAckWithExactlyRequiredCapabilityIsValid()
            throws Exception {
        ClusterMediaProtocol.validateHelloAck(
                new byte[]{0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01});

        // ARTWORK may be advertised alongside MEDIA_STATE.
        ClusterMediaProtocol.validateHelloAck(
                new byte[]{0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03});
    }

    @Test
    public void helloAckRejectsInvalidPayloads() {
        byte[][] invalid = {
                // Wrong length.
                new byte[]{0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x01},
                // Version 2.
                new byte[]{0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01},
                // Non-zero reserved.
                new byte[]{0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01},
                // Missing MEDIA_STATE capability.
                new byte[]{0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00},
                // ARTWORK alone never satisfies the handshake.
                new byte[]{0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02},
        };

        for (byte[] payload : invalid) {
            try {
                ClusterMediaProtocol.validateHelloAck(payload);
                fail("expected rejection for " + Arrays.toString(payload));
            } catch (ClusterMediaProtocol.ProtocolException expected) {
                // Ignored.
            }
        }

        try {
            ClusterMediaProtocol.validateHelloAck(null);
            fail("null HELLO_ACK payload must be rejected");
        } catch (ClusterMediaProtocol.ProtocolException expected) {
            // Ignored.
        }
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }
}
