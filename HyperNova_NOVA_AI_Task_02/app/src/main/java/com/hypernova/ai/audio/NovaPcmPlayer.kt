package com.hypernova.ai.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.hypernova.ai.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class NovaPcmPlayer(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPlaybackChanged(playing: Boolean, turnId: String?)
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) stop()
        }
        .build()

    private var track: AudioTrack? = null
    private var streamId = 0L
    private var turnId: String? = null
    private var sampleRate = 0
    private var pendingPcm: ByteArrayOutputStream? = null

    @Synchronized
    fun start(streamId: Long, metadata: ByteArray) {
        stop()
        val objectMetadata = JSONObject(String(metadata, Charsets.UTF_8))
        sampleRate = objectMetadata.optInt("sample_rate", 22_050)
        turnId = objectMetadata.optString("turn_id").takeIf { it.isNotBlank() }
        applyConfiguredAssistantVolume()

        val focusResult = audioManager.requestAudioFocus(focusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "TTS playback could not acquire audio focus")
            turnId = null
            return
        }

        val minimumBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) {
            Log.e(TAG, "Unsupported TTS sample rate $sampleRate")
            audioManager.abandonAudioFocusRequest(focusRequest)
            turnId = null
            return
        }

        this.streamId = streamId
        pendingPcm = ByteArrayOutputStream(maxOf(minimumBuffer, sampleRate))
    }

    private fun applyConfiguredAssistantVolume() {
        val requested = BuildConfig.NOVA_ASSISTANT_VOLUME_INDEX
        if (requested < 0) return
        try {
            // The phone emulator routes USAGE_ASSISTANT through the media mixer but does not
            // expose Android Automotive's private assistant stream (stream type 11). Querying that
            // stream throws and used to tear down the whole PCM socket before playback began.
            val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = requested.coerceIn(
                audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC),
                maximum,
            )
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != target) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
        } catch (error: RuntimeException) {
            // Volume is a presentation preference, never a reason to drop response audio.
            Log.w(TAG, "Could not apply the debug assistant volume preset", error)
        }
    }

    @Synchronized
    fun write(streamId: Long, pcm: ByteArray) {
        if (this.streamId != streamId) return
        pendingPcm?.write(pcm)
    }

    @Synchronized
    fun end(streamId: Long) {
        if (this.streamId != streamId) return
        val pcm = pendingPcm?.toByteArray() ?: byteArrayOf()
        if (pcm.isEmpty() || sampleRate <= 0) {
            stop()
            return
        }

        val activeTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = activeTrack
        val written = activeTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        if (written < 0) {
            Log.w(TAG, "Static AudioTrack write failed: $written")
            stop()
            return
        }
        activeTrack.setVolume(1f)
        activeTrack.play()
        // This callback is the Pi's time-to-first-word marker. TTS_START means only that buffering
        // began; reporting "started" before AudioTrack.play() produced optimistic latency numbers
        // and showed SPEAKING while the cockpit was still silent.
        listener.onPlaybackChanged(true, turnId)

        val playbackMs = (written.toLong() / BYTES_PER_SAMPLE * 1_000L / sampleRate) + PLAYBACK_TAIL_MS
        try {
            Thread.sleep(playbackMs.coerceAtMost(MAX_PLAYBACK_MS))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        stop()
    }

    @Synchronized
    fun stop() {
        val wasPlaying = streamId != 0L
        try {
            track?.stop()
        } catch (_: Exception) {
            // The track may not have reached PLAYSTATE_PLAYING.
        }
        track?.release()
        track = null
        streamId = 0L
        sampleRate = 0
        pendingPcm = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        if (wasPlaying) listener.onPlaybackChanged(false, turnId)
        turnId = null
    }

    private companion object {
        const val TAG = "NovaPcmPlayer"
        const val BYTES_PER_SAMPLE = 2
        const val PLAYBACK_TAIL_MS = 80L
        const val MAX_PLAYBACK_MS = 30_000L
    }
}
