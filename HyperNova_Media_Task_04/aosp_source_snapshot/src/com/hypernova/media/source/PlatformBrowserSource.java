package com.hypernova.media.source;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.hypernova.media.model.MediaItemModel;
import com.hypernova.media.model.MediaSnapshot;
import com.hypernova.media.model.MediaSourceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bridges a platform or MediaBrowserServiceCompat provider through the framework media APIs.
 * This deliberately avoids an app-level dependency on AndroidX Media3.
 */
public class PlatformBrowserSource implements MediaSource {
    private static final String TAG = "HyperNovaMedia";

    protected final Context mContext;
    private final MediaSourceType mType;
    private final ComponentName mComponent;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private Listener mListener;
    private MediaBrowser mBrowser;
    private MediaController mController;
    private MediaSnapshot mSnapshot;
    private List<MediaItemModel> mItems = Collections.emptyList();
    private boolean mActive;
    private int mGeneration;

    public PlatformBrowserSource(Context context, MediaSourceType type, ComponentName component) {
        mContext = context.getApplicationContext();
        mType = type;
        mComponent = component;
        mSnapshot = MediaSnapshot.builder(type, initialState()).build();
    }

    protected MediaSnapshot.State initialState() {
        return isProviderInstalled() ? MediaSnapshot.State.IDLE : MediaSnapshot.State.UNAVAILABLE;
    }

    protected final boolean isProviderInstalled() {
        try {
            mContext.getPackageManager().getServiceInfo(mComponent, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public ComponentName getComponent() {
        return mComponent;
    }

    @Override
    public final MediaSourceType getType() {
        return mType;
    }

    @Override
    public final MediaSnapshot getSnapshot() {
        return mSnapshot;
    }

    @Override
    public final void setListener(Listener listener) {
        mListener = listener;
    }

    @Override
    public void activate() {
        mActive = true;
        connect();
    }

    private void connect() {
        disconnect();
        if (!isProviderInstalled()) {
            publish(MediaSnapshot.builder(mType, MediaSnapshot.State.UNAVAILABLE).build());
            return;
        }
        int generation = ++mGeneration;
        publish(MediaSnapshot.builder(mType, MediaSnapshot.State.CONNECTING)
                .deviceName(getDeviceName())
                .build());
        mBrowser = new MediaBrowser(mContext, mComponent, new MediaBrowser.ConnectionCallback() {
            @Override
            public void onConnected() {
                if (generation != mGeneration || mBrowser == null || !mBrowser.isConnected()) {
                    return;
                }
                try {
                    mController = new MediaController(mContext, mBrowser.getSessionToken());
                    mController.registerCallback(mControllerCallback, mMainHandler);
                    String root = mBrowser.getRoot();
                    if (root != null) {
                        mBrowser.subscribe(root, mSubscriptionCallback);
                    }
                    refreshFromController();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Unable to attach controller to " + mComponent, e);
                    publish(MediaSnapshot.builder(mType, MediaSnapshot.State.ERROR)
                            .deviceName(getDeviceName())
                            .errorDetail(e.getMessage())
                            .build());
                }
            }

            @Override
            public void onConnectionSuspended() {
                if (generation == mGeneration) {
                    publish(MediaSnapshot.builder(mType, disconnectedState())
                            .deviceName(getDeviceName())
                            .build());
                }
            }

            @Override
            public void onConnectionFailed() {
                if (generation == mGeneration) {
                    publish(MediaSnapshot.builder(mType, unavailableState())
                            .deviceName(getDeviceName())
                            .build());
                }
            }
        }, null);
        try {
            mBrowser.connect();
        } catch (RuntimeException e) {
            Log.e(TAG, "MediaBrowser connection failed for " + mComponent, e);
            publish(MediaSnapshot.builder(mType, unavailableState())
                    .deviceName(getDeviceName())
                    .errorDetail(e.getMessage())
                    .build());
        }
    }

    protected MediaSnapshot.State disconnectedState() {
        return MediaSnapshot.State.DISCONNECTED;
    }

    protected MediaSnapshot.State unavailableState() {
        return MediaSnapshot.State.UNAVAILABLE;
    }

    protected String getDeviceName() {
        return null;
    }

    @Override
    public void deactivate() {
        mActive = false;
        disconnect();
    }

    private void disconnect() {
        ++mGeneration;
        if (mController != null) {
            mController.unregisterCallback(mControllerCallback);
            mController = null;
        }
        if (mBrowser != null) {
            try {
                if (mBrowser.isConnected()) {
                    String root = mBrowser.getRoot();
                    if (root != null) {
                        mBrowser.unsubscribe(root);
                    }
                }
                mBrowser.disconnect();
            } catch (RuntimeException e) {
                Log.w(TAG, "MediaBrowser disconnect failed for " + mComponent, e);
            }
            mBrowser = null;
        }
    }

    @Override
    public void retry() {
        if (mActive) {
            connect();
        }
    }

    @Override
    public void play() {
        if (mController != null) {
            mController.getTransportControls().play();
        }
    }

    @Override
    public void pause() {
        if (mController != null) {
            PlaybackState state = mController.getPlaybackState();
            long actions = state == null ? 0 : state.getActions();
            if (hasAction(actions, PlaybackState.ACTION_PAUSE)
                    || hasAction(actions, PlaybackState.ACTION_PLAY_PAUSE)) {
                mController.getTransportControls().pause();
            } else if (hasAction(actions, PlaybackState.ACTION_STOP)) {
                // Broadcast radio commonly exposes STOP as its mute/off operation.
                mController.getTransportControls().stop();
            }
        }
    }

    @Override
    public void previous() {
        if (mController != null) {
            mController.getTransportControls().skipToPrevious();
        }
    }

    @Override
    public void next() {
        if (mController != null) {
            mController.getTransportControls().skipToNext();
        }
    }

    @Override
    public void seekTo(long positionMs) {
        if (mController != null) {
            mController.getTransportControls().seekTo(Math.max(0, positionMs));
        }
    }

    @Override
    public void playMediaId(String mediaId) {
        if (mController != null && mediaId != null) {
            mController.getTransportControls().playFromMediaId(mediaId, null);
        }
    }

    @Override
    public void setFavorite(boolean favorite) {
        if (mController != null && mController.getRatingType() == Rating.RATING_HEART) {
            mController.getTransportControls().setRating(Rating.newHeartRating(favorite));
        }
    }

    @Override
    public void release() {
        mActive = false;
        disconnect();
        mListener = null;
    }

    protected final void refreshFromController() {
        if (mController == null) {
            return;
        }
        publish(createSnapshot(mController.getMetadata(), mController.getPlaybackState(), mItems));
    }

    protected MediaSnapshot createSnapshot(MediaMetadata metadata, PlaybackState playback,
            List<MediaItemModel> items) {
        MediaSnapshot.State state = mapState(playback);
        long actions = playback == null ? 0 : playback.getActions();
        MediaSnapshot.Builder builder = MediaSnapshot.builder(mType, state)
                .deviceName(getDeviceName())
                .items(items)
                .canBrowse(!items.isEmpty())
                .canPlay(hasAction(actions, PlaybackState.ACTION_PLAY)
                        || hasAction(actions, PlaybackState.ACTION_PLAY_PAUSE))
                .canPause(hasAction(actions, PlaybackState.ACTION_PAUSE)
                        || hasAction(actions, PlaybackState.ACTION_PLAY_PAUSE)
                        || hasAction(actions, PlaybackState.ACTION_STOP))
                .canPrevious(hasAction(actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS))
                .canNext(hasAction(actions, PlaybackState.ACTION_SKIP_TO_NEXT))
                .canSeek(hasAction(actions, PlaybackState.ACTION_SEEK_TO));
        boolean supportsFavorite = mController != null
                && mController.getRatingType() == Rating.RATING_HEART;
        builder.supportsFavorite(supportsFavorite);
        if (playback != null) {
            builder.position(playback.getPosition(), playback.getLastPositionUpdateTime())
                    .speed(playback.getPlaybackSpeed())
                    .errorDetail(playback.getErrorMessage() == null
                            ? null : playback.getErrorMessage().toString());
        }
        if (metadata != null) {
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (TextUtils.isEmpty(title)) {
                title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            }
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(artist)) {
                artist = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            }
            String artwork = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (TextUtils.isEmpty(artwork)) {
                artwork = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
            }
            builder.title(title)
                    .artist(artist)
                    .album(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))
                    .artworkUri(artwork)
                    .artwork(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
                            ? metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                            : metadata.getBitmap(MediaMetadata.METADATA_KEY_ART))
                    .duration(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            Rating rating = metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
            if (supportsFavorite && rating != null && rating.isRated()) {
                builder.favorite(rating.hasHeart());
            }
        }
        return builder.build();
    }

    protected MediaSnapshot.State mapState(PlaybackState playback) {
        if (playback == null) {
            return MediaSnapshot.State.READY;
        }
        switch (playback.getState()) {
            case PlaybackState.STATE_PLAYING:
                return MediaSnapshot.State.PLAYING;
            case PlaybackState.STATE_PAUSED:
                return MediaSnapshot.State.PAUSED;
            case PlaybackState.STATE_BUFFERING:
                return MediaSnapshot.State.BUFFERING;
            case PlaybackState.STATE_CONNECTING:
                return MediaSnapshot.State.CONNECTING;
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                return MediaSnapshot.State.SEEKING;
            case PlaybackState.STATE_ERROR:
                return errorPlaybackState();
            case PlaybackState.STATE_STOPPED:
            case PlaybackState.STATE_NONE:
            default:
                return MediaSnapshot.State.READY;
        }
    }

    protected MediaSnapshot.State errorPlaybackState() {
        return MediaSnapshot.State.ERROR;
    }

    private static boolean hasAction(long actions, long action) {
        return (actions & action) != 0;
    }

    private final MediaController.Callback mControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            refreshFromController();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            refreshFromController();
        }

        @Override
        public void onQueueChanged(List<android.media.session.MediaSession.QueueItem> queue) {
            if (queue == null || queue.isEmpty()) {
                return;
            }
            List<MediaItemModel> queueItems = new ArrayList<>();
            for (android.media.session.MediaSession.QueueItem item : queue) {
                MediaItemModel converted = convert(item.getDescription());
                if (converted != null) {
                    queueItems.add(converted);
                }
            }
            mItems = queueItems;
            refreshFromController();
        }

        @Override
        public void onSessionDestroyed() {
            if (mActive) {
                connect();
            }
        }
    };

    private final MediaBrowser.SubscriptionCallback mSubscriptionCallback =
            new MediaBrowser.SubscriptionCallback() {
                @Override
                public void onChildrenLoaded(String parentId, List<MediaBrowser.MediaItem> children) {
                    List<MediaItemModel> result = new ArrayList<>();
                    for (MediaBrowser.MediaItem child : children) {
                        MediaItemModel converted = convert(child.getDescription());
                        if (converted != null) {
                            result.add(converted);
                        }
                    }
                    mItems = result;
                    refreshFromController();
                }

                @Override
                public void onError(String parentId) {
                    Log.w(TAG, "Browse error from " + mComponent + " for " + parentId);
                }
            };

    private static MediaItemModel convert(MediaDescription description) {
        if (description == null || description.getMediaId() == null) {
            return null;
        }
        Bundle extras = description.getExtras();
        long duration = extras == null ? 0
                : extras.getLong(MediaMetadata.METADATA_KEY_DURATION, 0);
        Uri artwork = description.getIconUri();
        return new MediaItemModel(
                description.getMediaId(),
                description.getMediaUri(),
                charSequence(description.getTitle()),
                charSequence(description.getSubtitle()),
                charSequence(description.getDescription()),
                null,
                null,
                null,
                artwork,
                duration,
                false,
                0,
                0);
    }

    private static String charSequence(CharSequence value) {
        return value == null ? null : value.toString();
    }

    private void publish(MediaSnapshot snapshot) {
        mSnapshot = snapshot;
        Listener listener = mListener;
        if (listener != null) {
            listener.onSourceChanged(this, snapshot);
        }
    }
}
