package com.hypernova.launcher.core.media

import android.net.Uri
import com.hypernova.launcher.core.state.AppConnectionState

/**
 * A read-only snapshot received from HyperNova Media.
 *
 * The launcher displays this state but does not own it.
 */
data class MediaSessionSnapshot(
    val connectionState: AppConnectionState,
    val hasActiveSession: Boolean,
    val hasActiveMediaItem: Boolean,
    val title: String?,
    val artist: String?,
    val artworkUri: Uri?,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val canPlayPause: Boolean,
    val canSkipPrevious: Boolean,
    val canSkipNext: Boolean,
    val errorMessage: String? = null
) {

    companion object {

        /**
         * Create an empty snapshot for a connection state.
         */
        fun empty(
            connectionState: AppConnectionState,
            errorMessage: String? = null
        ): MediaSessionSnapshot {
            return MediaSessionSnapshot(
                connectionState = connectionState,
                hasActiveSession = false,
                hasActiveMediaItem = false,
                title = null,
                artist = null,
                artworkUri = null,
                positionMs = 0L,
                durationMs = 0L,
                isPlaying = false,
                canPlayPause = false,
                canSkipPrevious = false,
                canSkipNext = false,
                errorMessage = errorMessage
            )
        }
    }
}