package com.hypernova.media.model;

import android.net.Uri;
import android.text.TextUtils;

import java.util.Objects;

/** Immutable description of real media exposed by an Android media provider or MediaStore. */
public final class MediaItemModel {
    private final String mId;
    private final Uri mUri;
    private final String mTitle;
    private final String mArtist;
    private final String mAlbum;
    private final String mGenre;
    private final String mFolder;
    private final String mMimeType;
    private final Uri mArtworkUri;
    private final long mDurationMs;
    private final boolean mVideo;
    private final int mWidth;
    private final int mHeight;

    public MediaItemModel(String id, Uri uri, String title, String artist, String album,
            String genre, String folder, String mimeType, Uri artworkUri, long durationMs,
            boolean video, int width, int height) {
        mId = Objects.requireNonNull(id);
        mUri = uri;
        mTitle = emptyToNull(title);
        mArtist = emptyToNull(artist);
        mAlbum = emptyToNull(album);
        mGenre = emptyToNull(genre);
        mFolder = emptyToNull(folder);
        mMimeType = emptyToNull(mimeType);
        mArtworkUri = artworkUri;
        mDurationMs = Math.max(0, durationMs);
        mVideo = video;
        mWidth = Math.max(0, width);
        mHeight = Math.max(0, height);
    }

    private static String emptyToNull(String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }

    public String getId() {
        return mId;
    }

    public Uri getUri() {
        return mUri;
    }

    public String getTitle() {
        return mTitle;
    }

    public String getArtist() {
        return mArtist;
    }

    public String getAlbum() {
        return mAlbum;
    }

    public String getGenre() {
        return mGenre;
    }

    public String getFolder() {
        return mFolder;
    }

    public String getMimeType() {
        return mMimeType;
    }

    public Uri getArtworkUri() {
        return mArtworkUri;
    }

    public long getDurationMs() {
        return mDurationMs;
    }

    public boolean isVideo() {
        return mVideo;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }
}
