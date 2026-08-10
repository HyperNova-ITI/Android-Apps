package com.hypernova.media.model;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;

public final class PlaybackUiState {
    public static final PlaybackUiState DISCONNECTED = new PlaybackUiState(
            false, false, Player.STATE_IDLE, 0L, 0L, null, null);

    public final boolean connected;
    public final boolean playing;
    public final int playbackState;
    public final long positionMs;
    public final long durationMs;
    @Nullable public final MediaItemModel item;
    @Nullable public final String error;

    public PlaybackUiState(boolean connected, boolean playing, int playbackState,
            long positionMs, long durationMs, @Nullable MediaItemModel item,
            @Nullable String error) {
        this.connected = connected;
        this.playing = playing;
        this.playbackState = playbackState;
        this.positionMs = Math.max(0L, positionMs);
        this.durationMs = Math.max(0L, durationMs);
        this.item = item;
        this.error = error;
    }

    public boolean isBuffering() { return playbackState == Player.STATE_BUFFERING; }
    public boolean hasMedia() { return item != null; }
}
