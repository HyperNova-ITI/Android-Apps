package com.hypernova.ai.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class AudioFrameType(val wireValue: Int) {
    HELLO(1),
    HELLO_ACK(2),
    MIC_START(16),
    MIC_PCM(17),
    MIC_END(18),
    TTS_START(32),
    TTS_PCM(33),
    TTS_END(34),
    PING(48),
    PONG(49),
    AUDIO_ERROR(255);

    companion object {
        fun fromWireValue(value: Int): AudioFrameType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw AudioProtocolException("Unknown frame type $value")
    }
}

data class AudioFrame(
    val type: AudioFrameType,
    val streamId: Long = 0,
    val payload: ByteArray = byteArrayOf(),
    val flags: Int = 0,
    val version: Int = AudioFrameCodec.VERSION,
)

class AudioProtocolException(message: String) : Exception(message)

object AudioFrameCodec {
    const val VERSION = 1
    const val HEADER_SIZE = 16
    const val MAX_PAYLOAD_SIZE = 262_144
    private val magic = byteArrayOf('N'.code.toByte(), 'V'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())

    fun write(frame: AudioFrame, output: OutputStream) {
        require(frame.version == VERSION) { "Unsupported outgoing protocol version ${frame.version}" }
        require(frame.flags in 0..0xffff) { "Flags must fit in 16 bits" }
        require(frame.streamId in 0..0xffff_ffffL) { "Stream ID must fit in 32 bits" }
        require(frame.payload.size <= MAX_PAYLOAD_SIZE) { "Audio payload is too large" }

        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(magic)
            .put(frame.version.toByte())
            .put(frame.type.wireValue.toByte())
            .putShort(frame.flags.toShort())
            .putInt(frame.payload.size)
            .putInt(frame.streamId.toInt())
            .array()

        output.write(header)
        output.write(frame.payload)
        output.flush()
    }

    fun read(input: InputStream): AudioFrame? {
        val header = readExactlyOrNull(input, HEADER_SIZE) ?: return null
        if (!header.copyOfRange(0, 4).contentEquals(magic)) {
            throw AudioProtocolException("Invalid audio frame magic")
        }

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val version = buffer.get().toInt() and 0xff
        if (version != VERSION) throw AudioProtocolException("Unsupported protocol version $version")

        val type = AudioFrameType.fromWireValue(buffer.get().toInt() and 0xff)
        val flags = buffer.short.toInt() and 0xffff
        val payloadSize = buffer.int
        if (payloadSize !in 0..MAX_PAYLOAD_SIZE) {
            throw AudioProtocolException("Invalid payload size $payloadSize")
        }
        val streamId = buffer.int.toLong() and 0xffff_ffffL
        val payload = if (payloadSize == 0) byteArrayOf() else readExactly(input, payloadSize)

        return AudioFrame(type, streamId, payload, flags, version)
    }

    private fun readExactlyOrNull(input: InputStream, size: Int): ByteArray? {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(result, offset, size - offset)
            if (read == -1) {
                if (offset == 0) return null
                throw EOFException("Connection ended in the middle of an audio frame")
            }
            offset += read
        }
        return result
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray =
        readExactlyOrNull(input, size) ?: throw EOFException("Missing audio frame payload")
}
