package com.hypernova.media.playback;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.hypernova.media.model.MediaItemModel;
import com.hypernova.media.model.PlaybackUiState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Activity-safe controller proxy. Commands are sent to the service-owned player. */
public final class PlaybackController implements Player.Listener {
    public interface Listener { void onPlaybackStateChanged(PlaybackUiState state); }

    private static final String TAG = "HyperNovaPlayback";
    private final Context context;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private PlaybackUiState state = PlaybackUiState.DISCONNECTED;
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            publish();
            handler.postDelayed(this, controller != null && controller.isPlaying() ? 500L : 1500L);
        }
    };

    public PlaybackController(Context context) {
        this.context = context.getApplicationContext();
        connect();
    }

    private void connect() {
        SessionToken token = new SessionToken(context,
                new ComponentName(context, HyperNovaPlaybackService.class));
        controllerFuture = new MediaController.Builder(context, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(this);
                handler.removeCallbacks(progressTicker);
                handler.post(progressTicker);
                publish();
            } catch (Exception error) {
                Log.e(TAG, "Unable to connect to MediaSessionService", error);
                state = new PlaybackUiState(false, false, Player.STATE_IDLE,
                        0L, 0L, null, "Playback service unavailable");
                dispatch();
            }
        }, ContextCompat.getMainExecutor(context));
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onPlaybackStateChanged(state);
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }
    @Nullable public MediaController getPlayer() { return controller; }
    public PlaybackUiState getState() { return state; }

    public void playQueue(List<MediaItemModel> queue, int index) {
        if (controller == null || queue.isEmpty()) return;
        List<MediaItem> items = new ArrayList<>();
        for (MediaItemModel model : queue) items.add(toMediaItem(model));
        controller.setMediaItems(items, Math.max(0, Math.min(index, items.size() - 1)), 0L);
        controller.prepare();
        controller.play();
    }

    public void playSingle(MediaItemModel item) {
        if (controller == null) return;
        controller.setMediaItem(toMediaItem(item));
        controller.prepare();
        controller.play();
    }

    private MediaItem toMediaItem(MediaItemModel model) {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .setTitle(model.getTitle())
                .setArtist(model.getArtist())
                .setAlbumTitle(model.getAlbum())
                .setGenre(model.getGenre())
                .setIsBrowsable(false)
                .setIsPlayable(true);
        if (model.getArtworkUri() != null) metadata.setArtworkUri(model.getArtworkUri());
        return new MediaItem.Builder()
                .setMediaId(model.getId())
                .setUri(model.getUri())
                .setMimeType(model.getMimeType().isEmpty() ? null : model.getMimeType())
                .setMediaMetadata(metadata.build())
                .build();
    }

    public void playPause() {
        if (controller == null) return;
        if (controller.isPlaying()) controller.pause();
        else {
            if (controller.getPlaybackState() == Player.STATE_IDLE) controller.prepare();
            else if (controller.getPlaybackState() == Player.STATE_ENDED) {
                controller.seekToDefaultPosition();
            }
            controller.play();
        }
    }
    public void play() {
        if (controller == null) return;
        if (controller.getPlaybackState() == Player.STATE_ENDED) controller.seekToDefaultPosition();
        controller.play();
    }
    public void pause() { if (controller != null) controller.pause(); }
    public void stopAndClear() {
        if (controller == null) return;
        controller.stop();
        controller.clearMediaItems();
    }
    public void previous() { if (controller != null) controller.seekToPreviousMediaItem(); }
    public void next() { if (controller != null) controller.seekToNextMediaItem(); }
    public void seekTo(long positionMs) { if (controller != null) controller.seekTo(positionMs); }
    public void seekBy(long offsetMs) {
        if (controller != null) controller.seekTo(Math.max(0L, controller.getCurrentPosition() + offsetMs));
    }
    public void setShuffle(boolean enabled) {
        if (controller != null) controller.setShuffleModeEnabled(enabled);
    }
    public void cycleRepeat() {
        if (controller == null) return;
        int mode = controller.getRepeatMode();
        controller.setRepeatMode(mode == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL
                : mode == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }
    public void setSpeed(float speed) {
        if (controller != null) controller.setPlaybackSpeed(speed);
    }
    public List<String> getQueueTitles() {
        if (controller == null) return Collections.emptyList();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
            CharSequence title = controller.getMediaItemAt(i).mediaMetadata.title;
            titles.add(title == null ? "Untitled media" : title.toString());
        }
        return titles;
    }
    public int getCurrentQueueIndex() { return controller == null ? -1 : controller.getCurrentMediaItemIndex(); }
    public void skipToQueueIndex(int index) {
        if (controller != null && index >= 0 && index < controller.getMediaItemCount()) {
            controller.seekToDefaultPosition(index);
            controller.play();
        }
    }
    public void retry() {
        if (controller != null) { controller.prepare(); controller.play(); }
    }

    @Override public void onEvents(Player player, Player.Events events) { publish(); }
    @Override public void onPlayerError(PlaybackException error) {
        Log.e(TAG, "Player error " + error.getErrorCodeName(), error);
        publish();
    }

    private void publish() {
        if (controller == null) return;
        MediaItemModel item = null;
        MediaItem current = controller.getCurrentMediaItem();
        if (current != null && current.localConfiguration != null) {
            MediaMetadata metadata = current.mediaMetadata;
            String title = metadata.title == null ? "Unknown media" : metadata.title.toString();
            item = new MediaItemModel(current.mediaId, current.localConfiguration.uri, title,
                    text(metadata.artist), text(metadata.albumTitle), text(metadata.genre), "",
                    current.localConfiguration.mimeType, metadata.artworkUri,
                    Math.max(0L, controller.getDuration()),
                    current.localConfiguration.mimeType != null
                            && current.localConfiguration.mimeType.startsWith("video/"));
        }
        PlaybackException error = controller.getPlayerError();
        state = new PlaybackUiState(true, controller.isPlaying(), controller.getPlaybackState(),
                controller.getCurrentPosition(), Math.max(0L, controller.getDuration()), item,
                error == null ? null : error.getErrorCodeName());
        dispatch();
    }

    private String text(@Nullable CharSequence value) { return value == null ? "" : value.toString(); }
    private void dispatch() { for (Listener listener : listeners) listener.onPlaybackStateChanged(state); }
}
