package com.hypernova.ai.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.hypernova.ai.network.NovaAudioClient

class NovaAudioCapture(
    private val context: Context,
    private val audioClient: NovaAudioClient,
) {
    @Volatile private var running = false
    @Volatile private var transmissionEnabled = false
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null

    fun start(): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        running = true
        worker = Thread(::captureLoop, "nova-microphone").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // A blocked read exits when AudioRecord stops.
        }
        worker?.interrupt()
        worker = null
        recorder?.release()
        recorder = null
    }

    fun setTransmissionEnabled(enabled: Boolean) {
        transmissionEnabled = enabled
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        val frameBytes = SAMPLE_RATE * FRAME_MS / 1_000 * BYTES_PER_SAMPLE
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) {
            Log.e(TAG, "16 kHz mono microphone capture is not supported: $minimumBuffer")
            running = false
            return
        }

        val localRecorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build())
            .setBufferSizeInBytes(maxOf(minimumBuffer, frameBytes * 8))
            .build()
        recorder = localRecorder

        if (localRecorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord did not initialize")
            localRecorder.release()
            recorder = null
            running = false
            return
        }

        val gate = SpeechGate()
        var streamId = 0L
        var wasEnabled = false
        val frame = ByteArray(frameBytes)

        try {
            localRecorder.startRecording()
            while (running) {
                val bytesRead = localRecorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (bytesRead < 0) {
                    Log.w(TAG, "AudioRecord read failed: $bytesRead")
                    break
                }
                if (bytesRead != frame.size) continue

                val enabled = transmissionEnabled
                if (!enabled) {
                    if (wasEnabled && streamId != 0L) audioClient.sendMicEnd(streamId)
                    gate.reset()
                    streamId = 0L
                    wasEnabled = false
                    continue
                }
                wasEnabled = true

                for (event in gate.accept(frame)) {
                    when (event) {
                        is SpeechGateEvent.Start -> {
                            streamId = audioClient.nextStreamId()
                            if (!audioClient.sendMicStart(streamId)) {
                                gate.reset()
                                streamId = 0L
                                continue
                            }
                            event.frames.forEach { audioClient.sendMicPcm(streamId, it) }
                        }
                        is SpeechGateEvent.Pcm -> if (streamId != 0L) {
                            audioClient.sendMicPcm(streamId, event.frame)
                        }
                        SpeechGateEvent.End -> {
                            if (streamId != 0L) audioClient.sendMicEnd(streamId)
                            streamId = 0L
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (running) Log.e(TAG, "Microphone capture stopped unexpectedly", error)
        } finally {
            if (streamId != 0L) audioClient.sendMicEnd(streamId)
            try {
                localRecorder.stop()
            } catch (_: Exception) {
                // It may already be stopped by stop().
            }
            localRecorder.release()
            recorder = null
            running = false
        }
    }

    private companion object {
        const val TAG = "NovaAudioCapture"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val BYTES_PER_SAMPLE = 2
    }
}
