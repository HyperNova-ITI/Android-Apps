package com.hypernova.ai.network

import android.util.Log
import com.hypernova.ai.BuildConfig
import com.hypernova.ai.protocol.AudioFrame
import com.hypernova.ai.protocol.AudioFrameCodec
import com.hypernova.ai.protocol.AudioFrameType
import com.hypernova.ai.runtime.NovaEndpoint
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class NovaAudioClient(
    private val endpoint: NovaEndpoint,
    private val listener: Listener,
) {
    interface Listener {
        fun onAudioConnectionChanged(connected: Boolean)
        fun onAudioFrame(frame: AudioFrame)
    }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var output: BufferedOutputStream? = null
    private val writerLock = Any()
    private val streamSequence = AtomicLong(0)
    private val lifecycleGeneration = AtomicLong(0)
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val generation = lifecycleGeneration.incrementAndGet()
        worker = Thread({ runLoop(generation) }, "nova-audio-network").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        lifecycleGeneration.incrementAndGet()
        closeConnection()
        worker?.interrupt()
        worker = null
    }

    fun nextStreamId(): Long = streamSequence.updateAndGet { current ->
        if (current >= 0xffff_fffeL) 1 else current + 1
    }

    fun sendMicStart(streamId: Long): Boolean = send(
        AudioFrame(
            type = AudioFrameType.MIC_START,
            streamId = streamId,
            payload = JSONObject().apply {
                put("encoding", "pcm_s16le")
                put("sample_rate", 16_000)
                put("channels", 1)
                put("frame_ms", 20)
                put("pre_roll_ms", 600)
                put("vad_hangover_ms", 800)
            }.toString().toByteArray(Charsets.UTF_8),
        ),
    )

    fun sendMicPcm(streamId: Long, pcm: ByteArray): Boolean =
        send(AudioFrame(AudioFrameType.MIC_PCM, streamId, pcm))

    fun sendMicEnd(streamId: Long): Boolean =
        send(AudioFrame(AudioFrameType.MIC_END, streamId))

    fun send(frame: AudioFrame): Boolean = synchronized(writerLock) {
        val activeOutput = output ?: return@synchronized false
        try {
            AudioFrameCodec.write(frame, activeOutput)
            true
        } catch (error: Exception) {
            Log.w(TAG, "Audio send failed", error)
            false
        }
    }

    private fun runLoop(generation: Long) {
        var backoffMs = 250L
        while (isCurrent(generation)) {
            var connectedSocket: Socket? = null
            var authenticated = false
            try {
                connectedSocket = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(endpoint.host, endpoint.audioPort), CONNECT_TIMEOUT_MS)
                }
                val accepted = synchronized(writerLock) {
                    if (!isCurrent(generation)) {
                        false
                    } else {
                        socket = connectedSocket
                        output = BufferedOutputStream(connectedSocket.getOutputStream())
                        true
                    }
                }
                if (!accepted) break
                if (!sendHello()) throw IllegalStateException("audio hello send failed")
                backoffMs = 250L

                BufferedInputStream(connectedSocket.getInputStream()).use { input ->
                    while (isCurrent(generation)) {
                        val frame = AudioFrameCodec.read(input) ?: break
                        if (frame.type == AudioFrameType.HELLO_ACK) {
                            if (!authenticated) {
                                authenticated = true
                                listener.onAudioConnectionChanged(true)
                            }
                        } else if (frame.type == AudioFrameType.AUDIO_ERROR && !authenticated) {
                            throw SecurityException("Pi rejected NOVA audio authentication")
                        } else if (!authenticated) {
                            Log.w(TAG, "Ignored audio frame before authenticated hello: ${frame.type}")
                        } else if (frame.type == AudioFrameType.PING) {
                            send(AudioFrame(AudioFrameType.PONG))
                        } else {
                            try {
                                listener.onAudioFrame(frame)
                            } catch (error: RuntimeException) {
                                // A local AudioTrack/AudioManager failure must not corrupt the
                                // framing layer or force the Pi and Android into a reconnect loop.
                                Log.e(TAG, "Audio frame handler failed for ${frame.type}", error)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (isCurrent(generation)) {
                    Log.d(TAG, "Audio connection unavailable: ${error.message}")
                }
            } finally {
                closeConnection(connectedSocket)
                if (authenticated && isCurrent(generation)) {
                    listener.onAudioConnectionChanged(false)
                }
            }

            if (isCurrent(generation)) {
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                backoffMs = (backoffMs * 2).coerceAtMost(5_000L)
            }
        }
    }

    private fun isCurrent(generation: Long): Boolean =
        running && lifecycleGeneration.get() == generation

    private fun sendHello(): Boolean =
        send(AudioFrame(
            type = AudioFrameType.HELLO,
            payload = JSONObject().apply {
                put("client", "nova-android")
                put("mic", "none")
                put("tts", "pcm_s16le/mono")
                if (BuildConfig.NOVA_LINK_TOKEN.isNotBlank()) {
                    put("auth", BuildConfig.NOVA_LINK_TOKEN)
                }
            }.toString().toByteArray(Charsets.UTF_8),
        ))

    private fun closeConnection(expectedSocket: Socket? = null) {
        synchronized(writerLock) {
            val activeSocket = socket
            if (expectedSocket != null && activeSocket !== expectedSocket) {
                try {
                    expectedSocket.close()
                } catch (_: Exception) {
                    // This belongs to an obsolete worker and is already unusable.
                }
                return
            }
            output = null
            try {
                activeSocket?.close()
            } catch (_: Exception) {
                // The reconnect loop owns recovery.
            }
            socket = null
        }
    }

    private companion object {
        const val TAG = "NovaAudioClient"
        const val CONNECT_TIMEOUT_MS = 3_000
    }
}
