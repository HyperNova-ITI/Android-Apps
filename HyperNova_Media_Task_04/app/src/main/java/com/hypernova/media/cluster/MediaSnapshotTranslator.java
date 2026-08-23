package com.hypernova.media.cluster;

import androidx.annotation.Nullable;

import com.hypernova.contracts.media.MediaPlaybackSnapshot;
import com.hypernova.media.model.MediaItemModel;
import com.hypernova.media.model.PlaybackUiState;

public final class MediaSnapshotTranslator {
    private MediaSnapshotTranslator() {
    }

    public static MediaPlaybackSnapshot translate(PlaybackUiState state) {
        return translate(state, state.item);
    }

    public static MediaPlaybackSnapshot translate(PlaybackUiState state,
            @Nullable MediaItemModel item) {
        boolean hasMedia = item != null;
        return new MediaPlaybackSnapshot(
                hasMedia,
                state.playing,
                state.playbackState,
                state.positionMs,
                hasMedia ? state.durationMs : 0L,
                hasMedia ? item.getId() : "",
                hasMedia ? item.getTitle() : "",
                hasMedia ? item.getArtist() : "",
                hasMedia ? item.getAlbum() : "",
                hasMedia && item.getArtworkUri() != null
                        ? item.getArtworkUri().toString() : ""
        );
    }
}
