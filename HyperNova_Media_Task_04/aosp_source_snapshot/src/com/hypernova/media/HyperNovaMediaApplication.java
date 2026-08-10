package com.hypernova.media;

import android.app.Application;

import com.hypernova.media.playback.PlaybackCoordinator;

public final class HyperNovaMediaApplication extends Application {
    private PlaybackCoordinator mPlaybackCoordinator;

    @Override
    public void onCreate() {
        super.onCreate();
        mPlaybackCoordinator = new PlaybackCoordinator(this);
    }

    public PlaybackCoordinator getPlaybackCoordinator() {
        return mPlaybackCoordinator;
    }
}
