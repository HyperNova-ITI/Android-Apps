package com.hypernova.media.playback;

import android.car.Car;
import android.car.media.CarMediaManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.hypernova.media.bluetooth.BluetoothSource;
import com.hypernova.media.local.LocalMediaSource;
import com.hypernova.media.model.MediaSnapshot;
import com.hypernova.media.model.MediaSourceType;
import com.hypernova.media.radio.RadioSource;
import com.hypernova.media.source.MediaSource;
import com.hypernova.media.source.PlatformBrowserSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Owns source selection and presents one stable playback contract to UI and MediaSession. */
public final class PlaybackCoordinator implements MediaSource.Listener {
    private static final String TAG = "HyperNovaMedia";
    public static final ComponentName HYPERNOVA_SERVICE = new ComponentName(
            "com.hypernova.media",
            "com.hypernova.media.playback.HyperNovaMediaSessionService");

    public interface Listener {
        void onPlaybackChanged(MediaSourceType selectedSource, MediaSnapshot snapshot);
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Map<MediaSourceType, MediaSource> mSources =
            new EnumMap<>(MediaSourceType.class);
    private final List<Listener> mListeners = new ArrayList<>();
    private Car mCar;
    private CarMediaManager mCarMediaManager;
    private MediaSource mCurrentSource;

    public PlaybackCoordinator(Context context) {
        MediaSource radio = new RadioSource(context);
        MediaSource bluetooth = new BluetoothSource(context);
        MediaSource usb = new LocalMediaSource(context);
        addSource(radio);
        addSource(bluetooth);
        addSource(usb);
        connectCar(context);
    }

    private void addSource(MediaSource source) {
        source.setListener(this);
        mSources.put(source.getType(), source);
    }

    private void connectCar(Context context) {
        try {
            mCar = Car.createCar(context);
            if (mCar != null) {
                mCarMediaManager = (CarMediaManager) mCar.getCarManager(Car.CAR_MEDIA_SERVICE);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Car media service unavailable; direct media contracts remain active", e);
            mCar = null;
            mCarMediaManager = null;
        }
    }

    public void addListener(Listener listener) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mMainHandler.post(() -> addListener(listener));
            return;
        }
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
        listener.onPlaybackChanged(getSelectedSource(), getSnapshot());
    }

    public void removeListener(Listener listener) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mMainHandler.post(() -> removeListener(listener));
            return;
        }
        mListeners.remove(listener);
    }

    public MediaSourceType getSelectedSource() {
        return mCurrentSource == null ? null : mCurrentSource.getType();
    }

    public MediaSnapshot getSnapshot() {
        return mCurrentSource == null ? null : mCurrentSource.getSnapshot();
    }

    public MediaSnapshot getSourceSnapshot(MediaSourceType type) {
        MediaSource source = mSources.get(type);
        return source == null ? null : source.getSnapshot();
    }

    public void selectSource(MediaSourceType type) {
        runOnMain(() -> selectSourceOnMain(type));
    }

    private void selectSourceOnMain(MediaSourceType type) {
        MediaSource selected = mSources.get(type);
        if (selected == null) {
            return;
        }
        if (mCurrentSource == selected) {
            selected.retry();
            dispatch();
            return;
        }
        if (mCurrentSource != null) {
            MediaSnapshot previous = mCurrentSource.getSnapshot();
            if (previous != null && (previous.getState() == MediaSnapshot.State.PLAYING
                    || previous.getState() == MediaSnapshot.State.BUFFERING)) {
                mCurrentSource.pause();
            }
            mCurrentSource.deactivate();
        }
        mCurrentSource = selected;
        setPrimaryCarMediaSource(selected);
        dispatch();
        selected.activate();
    }

    private void setPrimaryCarMediaSource(MediaSource selected) {
        if (mCarMediaManager == null) {
            return;
        }
        // Radio and Bluetooth already own real platform MediaSessions. Making those providers
        // primary here and then publishing their state through the unified HyperNova session
        // makes CarMediaService bounce between two primary sources and stop the upstream player.
        // Their own sessions become primary naturally when playback starts. Local USB playback
        // has no separate provider session, so HyperNova is the correct AAOS source for it.
        if (selected instanceof PlatformBrowserSource) {
            return;
        }
        try {
            mCarMediaManager.setMediaSource(
                    HYPERNOVA_SERVICE, CarMediaManager.MEDIA_SOURCE_MODE_BROWSE);
            mCarMediaManager.setMediaSource(
                    HYPERNOVA_SERVICE, CarMediaManager.MEDIA_SOURCE_MODE_PLAYBACK);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to set HyperNova as the AAOS local media source", e);
        }
    }

    public void play() {
        runOnMain(() -> {
            if (mCurrentSource != null) mCurrentSource.play();
        });
    }

    public void pause() {
        runOnMain(() -> {
            if (mCurrentSource != null) mCurrentSource.pause();
        });
    }

    public void previous() {
        runOnMain(() -> {
            if (mCurrentSource != null) mCurrentSource.previous();
        });
    }

    public void next() {
        runOnMain(() -> {
            if (mCurrentSource != null) mCurrentSource.next();
        });
    }

    public void seekTo(long positionMs) {
        runOnMain(() -> {
            if (mCurrentSource != null) mCurrentSource.seekTo(positionMs);
        });
    }

    public void playMediaId(String mediaId) {
        runOnMain(() -> {
            if (mCurrentSource != null) mCurrentSource.playMediaId(mediaId);
        });
    }

    public void retry() {
        runOnMain(() -> {
            if (mCurrentSource != null) mCurrentSource.retry();
        });
    }

    public void setFavorite(boolean favorite) {
        runOnMain(() -> {
            if (mCurrentSource != null) mCurrentSource.setFavorite(favorite);
        });
    }

    public void setVideoSurface(Surface surface) {
        runOnMain(() -> {
            MediaSource usb = mSources.get(MediaSourceType.USB);
            if (usb != null) usb.setVideoSurface(surface);
        });
    }

    public void onPermissionsChanged() {
        runOnMain(() -> {
            if (mCurrentSource != null) {
                mCurrentSource.retry();
            }
        });
    }

    @Override
    public void onSourceChanged(MediaSource source, MediaSnapshot snapshot) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mMainHandler.post(() -> onSourceChanged(source, snapshot));
            return;
        }
        if (source == mCurrentSource) {
            dispatch();
        }
    }

    private void dispatch() {
        MediaSourceType selected = getSelectedSource();
        MediaSnapshot snapshot = getSnapshot();
        for (Listener listener : new ArrayList<>(mListeners)) {
            listener.onPlaybackChanged(selected, snapshot);
        }
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mMainHandler.post(runnable);
        }
    }
}
