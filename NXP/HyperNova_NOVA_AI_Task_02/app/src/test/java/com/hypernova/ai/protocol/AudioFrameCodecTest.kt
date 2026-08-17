package com.hypernova.ai.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AudioFrameCodecTest {
    @Test
    fun `frame round trips with network-order header`() {
        val expected = AudioFrame(
            type = AudioFrameType.MIC_PCM,
            streamId = 0xf000_0001L,
            payload = byteArrayOf(1, 2, 3, 4),
        )
        val bytes = ByteArrayOutputStream().also { AudioFrameCodec.write(expected, it) }.toByteArray()
        val actual = AudioFrameCodec.read(ByteArrayInputStream(bytes))!!

        assertEquals(expected.type, actual.type)
        assertEquals(expected.streamId, actual.streamId)
        assertArrayEquals(expected.payload, actual.payload)
        assertEquals(AudioFrameCodec.HEADER_SIZE + expected.payload.size, bytes.size)
    }

    @Test
    fun `clean end of stream returns null`() {
        assertNull(AudioFrameCodec.read(ByteArrayInputStream(byteArrayOf())))
    }
}
