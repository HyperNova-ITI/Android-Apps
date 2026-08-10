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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    private var playbackStarted = false
    private var playbackDone: CountDownLatch? = null

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

    fun end(streamId: Long) {
        val session = synchronized(this) {
            if (this.streamId != streamId) return
            val pcm = pendingPcm?.toByteArray() ?: byteArrayOf()
            if (pcm.isEmpty() || sampleRate <= 0) {
                stopLocked()
                return
            }

            try {
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
                if (written <= 0) {
                    Log.w(TAG, "Static AudioTrack write failed: $written")
                    stopLocked()
                    return
                }
                val done = CountDownLatch(1)
                playbackDone = done
                activeTrack.setVolume(1f)
                activeTrack.play()
                playbackStarted = true
                // This callback is the Pi's time-to-first-word marker. TTS_START means only that
                // buffering began; the cockpit enters SPEAKING only after AudioTrack.play().
                listener.onPlaybackChanged(true, turnId)
                PlaybackSession(
                    track = activeTrack,
                    done = done,
                    durationMs = (
                        written.toLong() / BYTES_PER_SAMPLE * 1_000L / sampleRate
                    ) + PLAYBACK_TAIL_MS,
                )
            } catch (error: RuntimeException) {
                Log.e(TAG, "Could not start assistant PCM playback", error)
                stopLocked()
                return
            }
        }

        // Do not hold the player monitor while audio drains. Audio-focus loss, service shutdown,
        // or a reconnect can now call stop() immediately instead of blocking for up to 30 seconds.
        try {
            session.done.await(
                session.durationMs.coerceAtMost(MAX_PLAYBACK_MS),
                TimeUnit.MILLISECONDS,
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(this) {
            if (track === session.track) stopLocked()
        }
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        val endedTurnId = turnId
        val reportEnded = playbackStarted
        playbackDone?.countDown()
        playbackDone = null
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
        playbackStarted = false
        audioManager.abandonAudioFocusRequest(focusRequest)
        turnId = null
        if (reportEnded) listener.onPlaybackChanged(false, endedTurnId)
    }

    private companion object {
        const val TAG = "NovaPcmPlayer"
        const val BYTES_PER_SAMPLE = 2
        const val PLAYBACK_TAIL_MS = 80L
        const val MAX_PLAYBACK_MS = 30_000L
    }

    private data class PlaybackSession(
        val track: AudioTrack,
        val done: CountDownLatch,
        val durationMs: Long,
    )
}
