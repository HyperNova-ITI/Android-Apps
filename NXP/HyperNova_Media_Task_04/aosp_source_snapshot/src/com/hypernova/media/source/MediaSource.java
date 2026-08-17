package com.hypernova.media.source;

import android.view.Surface;

import com.hypernova.media.model.MediaSnapshot;
import com.hypernova.media.model.MediaSourceType;

public interface MediaSource {
    interface Listener {
        void onSourceChanged(MediaSource source, MediaSnapshot snapshot);
    }

    MediaSourceType getType();
    MediaSnapshot getSnapshot();
    void setListener(Listener listener);
    void activate();
    void deactivate();
    void retry();
    void play();
    void pause();
    void previous();
    void next();
    void seekTo(long positionMs);
    void playMediaId(String mediaId);
    default void setFavorite(boolean favorite) {}
    default void setVideoSurface(Surface surface) {}
    void release();
}
