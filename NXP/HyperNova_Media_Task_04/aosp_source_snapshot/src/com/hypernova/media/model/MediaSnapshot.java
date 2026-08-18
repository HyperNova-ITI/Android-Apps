package com.hypernova.media.model;

import android.graphics.Bitmap;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A complete, immutable source and playback state for UI and MediaSession publication. */
public final class MediaSnapshot {
    public enum State {
        IDLE,
        LOADING,
        SCANNING,
        CONNECTING,
        READY,
        PLAYING,
        PAUSED,
        BUFFERING,
        SEEKING,
        FOCUS_INTERRUPTED,
        EMPTY,
        DISCONNECTED,
        UNAVAILABLE,
        PERMISSION_REQUIRED,
        ERROR
    }

    private final MediaSourceType mSource;
    private final State mState;
    private final String mTitle;
    private final String mArtist;
    private final String mAlbum;
    private final String mDeviceName;
    private final String mArtworkUri;
    private final Bitmap mArtwork;
    private final String mErrorDetail;
    private final long mPositionMs;
    private final long mPositionUpdateElapsedMs;
    private final long mDurationMs;
    private final float mPlaybackSpeed;
    private final boolean mCanPlay;
    private final boolean mCanPause;
    private final boolean mCanPrevious;
    private final boolean mCanNext;
    private final boolean mCanSeek;
    private final boolean mCanBrowse;
    private final boolean mSupportsFavorite;
    private final boolean mFavorite;
    private final boolean mVideo;
    private final int mVideoWidth;
    private final int mVideoHeight;
    private final List<MediaItemModel> mItems;

    private MediaSnapshot(Builder builder) {
        mSource = builder.mSource;
        mState = builder.mState;
        mTitle = builder.mTitle;
        mArtist = builder.mArtist;
        mAlbum = builder.mAlbum;
        mDeviceName = builder.mDeviceName;
        mArtworkUri = builder.mArtworkUri;
        mArtwork = builder.mArtwork;
        mErrorDetail = builder.mErrorDetail;
        mPositionMs = Math.max(0, builder.mPositionMs);
        mPositionUpdateElapsedMs = builder.mPositionUpdateElapsedMs;
        mDurationMs = Math.max(0, builder.mDurationMs);
        mPlaybackSpeed = builder.mPlaybackSpeed;
        mCanPlay = builder.mCanPlay;
        mCanPause = builder.mCanPause;
        mCanPrevious = builder.mCanPrevious;
        mCanNext = builder.mCanNext;
        mCanSeek = builder.mCanSeek;
        mCanBrowse = builder.mCanBrowse;
        mSupportsFavorite = builder.mSupportsFavorite;
        mFavorite = builder.mFavorite;
        mVideo = builder.mVideo;
        mVideoWidth = builder.mVideoWidth;
        mVideoHeight = builder.mVideoHeight;
        mItems = Collections.unmodifiableList(new ArrayList<>(builder.mItems));
    }

    public static Builder builder(MediaSourceType source, State state) {
        return new Builder(source, state);
    }

    public Builder buildUpon() {
        return new Builder(this);
    }

    public MediaSourceType getSource() { return mSource; }
    public State getState() { return mState; }
    public String getTitle() { return mTitle; }
    public String getArtist() { return mArtist; }
    public String getAlbum() { return mAlbum; }
    public String getDeviceName() { return mDeviceName; }
    public String getArtworkUri() { return mArtworkUri; }
    public Bitmap getArtwork() { return mArtwork; }
    public String getErrorDetail() { return mErrorDetail; }
    public long getDurationMs() { return mDurationMs; }
    public float getPlaybackSpeed() { return mPlaybackSpeed; }
    public boolean canPlay() { return mCanPlay; }
    public boolean canPause() { return mCanPause; }
    public boolean canPrevious() { return mCanPrevious; }
    public boolean canNext() { return mCanNext; }
    public boolean canSeek() { return mCanSeek; }
    public boolean canBrowse() { return mCanBrowse; }
    public boolean supportsFavorite() { return mSupportsFavorite; }
    public boolean isFavorite() { return mFavorite; }
    public boolean isVideo() { return mVideo; }
    public int getVideoWidth() { return mVideoWidth; }
    public int getVideoHeight() { return mVideoHeight; }
    public List<MediaItemModel> getItems() { return mItems; }

    public boolean hasActiveItem() {
        return mTitle != null;
    }

    /** Returns a clock-adjusted position without mutating state. */
    public long getCurrentPositionMs() {
        long result = mPositionMs;
        if (mState == State.PLAYING && mPlaybackSpeed != 0f) {
            long elapsed = Math.max(0, SystemClock.elapsedRealtime() - mPositionUpdateElapsedMs);
            result += (long) (elapsed * mPlaybackSpeed);
        }
        return mDurationMs > 0 ? Math.min(result, mDurationMs) : Math.max(0, result);
    }

    public static final class Builder {
        private final MediaSourceType mSource;
        private State mState;
        private String mTitle;
        private String mArtist;
        private String mAlbum;
        private String mDeviceName;
        private String mArtworkUri;
        private Bitmap mArtwork;
        private String mErrorDetail;
        private long mPositionMs;
        private long mPositionUpdateElapsedMs = SystemClock.elapsedRealtime();
        private long mDurationMs;
        private float mPlaybackSpeed;
        private boolean mCanPlay;
        private boolean mCanPause;
        private boolean mCanPrevious;
        private boolean mCanNext;
        private boolean mCanSeek;
        private boolean mCanBrowse;
        private boolean mSupportsFavorite;
        private boolean mFavorite;
        private boolean mVideo;
        private int mVideoWidth;
        private int mVideoHeight;
        private List<MediaItemModel> mItems = Collections.emptyList();

        private Builder(MediaSourceType source, State state) {
            mSource = source;
            mState = state;
        }

        private Builder(MediaSnapshot source) {
            mSource = source.mSource;
            mState = source.mState;
            mTitle = source.mTitle;
            mArtist = source.mArtist;
            mAlbum = source.mAlbum;
            mDeviceName = source.mDeviceName;
            mArtworkUri = source.mArtworkUri;
            mArtwork = source.mArtwork;
            mErrorDetail = source.mErrorDetail;
            mPositionMs = source.getCurrentPositionMs();
            mPositionUpdateElapsedMs = SystemClock.elapsedRealtime();
            mDurationMs = source.mDurationMs;
            mPlaybackSpeed = source.mPlaybackSpeed;
            mCanPlay = source.mCanPlay;
            mCanPause = source.mCanPause;
            mCanPrevious = source.mCanPrevious;
            mCanNext = source.mCanNext;
            mCanSeek = source.mCanSeek;
            mCanBrowse = source.mCanBrowse;
            mSupportsFavorite = source.mSupportsFavorite;
            mFavorite = source.mFavorite;
            mVideo = source.mVideo;
            mVideoWidth = source.mVideoWidth;
            mVideoHeight = source.mVideoHeight;
            mItems = source.mItems;
        }

        public Builder state(State state) { mState = state; return this; }
        public Builder title(String title) { mTitle = title; return this; }
        public Builder artist(String artist) { mArtist = artist; return this; }
        public Builder album(String album) { mAlbum = album; return this; }
        public Builder deviceName(String name) { mDeviceName = name; return this; }
        public Builder artworkUri(String uri) { mArtworkUri = uri; return this; }
        public Builder artwork(Bitmap artwork) { mArtwork = artwork; return this; }
        public Builder errorDetail(String detail) { mErrorDetail = detail; return this; }
        public Builder position(long positionMs, long updateElapsedMs) {
            mPositionMs = positionMs;
            mPositionUpdateElapsedMs = updateElapsedMs;
            return this;
        }
        public Builder duration(long durationMs) { mDurationMs = durationMs; return this; }
        public Builder speed(float speed) { mPlaybackSpeed = speed; return this; }
        public Builder canPlay(boolean value) { mCanPlay = value; return this; }
        public Builder canPause(boolean value) { mCanPause = value; return this; }
        public Builder canPrevious(boolean value) { mCanPrevious = value; return this; }
        public Builder canNext(boolean value) { mCanNext = value; return this; }
        public Builder canSeek(boolean value) { mCanSeek = value; return this; }
        public Builder canBrowse(boolean value) { mCanBrowse = value; return this; }
        public Builder supportsFavorite(boolean value) { mSupportsFavorite = value; return this; }
        public Builder favorite(boolean value) { mFavorite = value; return this; }
        public Builder video(boolean value) { mVideo = value; return this; }
        public Builder videoSize(int width, int height) {
            mVideoWidth = Math.max(0, width);
            mVideoHeight = Math.max(0, height);
            return this;
        }
        public Builder items(List<MediaItemModel> items) {
            mItems = items == null ? Collections.emptyList() : items;
            return this;
        }

        public MediaSnapshot build() {
            return new MediaSnapshot(this);
        }
    }
}
