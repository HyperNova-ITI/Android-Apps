package com.hypernova.media.radio;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;

import com.hypernova.media.model.MediaItemModel;
import com.hypernova.media.model.PlaybackUiState;
import com.hypernova.media.playback.PlaybackController;

import java.util.List;

public final class InternetRadioBackend implements RadioBackend, PlaybackController.Listener {
    public interface Listener { void onInternetRadioBackendChanged(); }
    private final RadioRepository repository;
    private final PlaybackController playback;
    @Nullable private RadioStation selected;
    @Nullable private String error;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int playGeneration;
    @Nullable private Listener listener;

    public InternetRadioBackend(RadioRepository repository, PlaybackController playback) {
        this.repository = repository;
        this.playback = playback;
        playback.addListener(this);
    }

    @Override public List<RadioStation> getStations() { return repository.all(); }
    @Nullable public RadioStation getSelected() { return selected; }
    @Nullable public String getError() { return error; }
    public void setListener(@Nullable Listener listener) { this.listener = listener; }

    @Override public void play(RadioStation station) {
        error = null;
        int token = ++playGeneration;
        selected = repository.markPlayed(station.id);
        if (selected == null) selected = station;
        Uri artwork = selected.artworkUrl.isEmpty() ? null : Uri.parse(selected.artworkUrl);
        String path = Uri.parse(selected.streamUrl).getPath();
        String mimeType = selected.hls || path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8")
                ? MimeTypes.APPLICATION_M3U8 : "";
        String artist = selected.locationLine();
        String album = selected.technicalLine();
        playback.playSingle(new MediaItemModel("radio:" + selected.id,
                Uri.parse(selected.streamUrl), selected.name, artist, album, selected.tags,
                "Radio", mimeType, artwork, 0L, false));
        handler.postDelayed(() -> {
            PlaybackUiState current = playback.getState();
            boolean sameItem = current.item == null
                    || current.item.getId().equals("radio:" + station.id);
            if (token == playGeneration && sameItem && !current.playing
                    && (current.error != null || current.playbackState != Player.STATE_READY)) {
                error = "The station did not begin streaming within 18 seconds.";
                playback.stopAndClear();
                notifyChanged();
            }
        }, 18_000L);
    }

    @Override public void previous() { move(-1); }
    @Override public void next() { move(1); }
    private void move(int direction) {
        List<RadioStation> values = repository.all();
        if (values.isEmpty()) return;
        int index = selected == null ? 0 : values.indexOf(selected);
        if (index < 0) index = 0;
        play(values.get((index + direction + values.size()) % values.size()));
    }
    @Override public void retry() {
        if (selected != null) play(selected);
        else playback.retry();
    }

    @Override public void onPlaybackStateChanged(PlaybackUiState state) {
        if (selected == null || state.item == null
                || !state.item.getId().equals("radio:" + selected.id)) return;
        if (state.playing) {
            error = null;
            repository.markVerified(selected.id);
        } else if (state.error != null) {
            error = state.error;
            notifyChanged();
        }
    }

    private void notifyChanged() { if (listener != null) listener.onInternetRadioBackendChanged(); }
}
