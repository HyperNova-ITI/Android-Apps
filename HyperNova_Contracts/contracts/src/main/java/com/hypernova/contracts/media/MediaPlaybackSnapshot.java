package com.hypernova.contracts.media;

import android.os.Parcel;
import android.os.Parcelable;

public final class MediaPlaybackSnapshot implements Parcelable {
    private final boolean hasMedia;
    private final boolean playing;
    private final int playbackState;
    private final long positionMs;
    private final long durationMs;
    private final String mediaId;
    private final String title;
    private final String artist;
    private final String album;
    private final String artworkUri;

    public MediaPlaybackSnapshot(
            boolean hasMedia,
            boolean playing,
            int playbackState,
            long positionMs,
            long durationMs,
            String mediaId,
            String title,
            String artist,
            String album,
            String artworkUri
    ) {
        this.hasMedia = hasMedia;
        this.playing = playing;
        this.playbackState = playbackState;
        this.positionMs = Math.max(0L, positionMs);
        this.durationMs = Math.max(0L, durationMs);
        this.mediaId = safe(mediaId);
        this.title = safe(title);
        this.artist = safe(artist);
        this.album = safe(album);
        this.artworkUri = safe(artworkUri);
    }

    private MediaPlaybackSnapshot(Parcel in) {
        hasMedia = in.readBoolean();
        playing = in.readBoolean();
        playbackState = in.readInt();
        positionMs = in.readLong();
        durationMs = in.readLong();
        mediaId = in.readString();
        title = in.readString();
        artist = in.readString();
        album = in.readString();
        artworkUri = in.readString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public boolean hasMedia() { return hasMedia; }
    public boolean isPlaying() { return playing; }
    public int getPlaybackState() { return playbackState; }
    public long getPositionMs() { return positionMs; }
    public long getDurationMs() { return durationMs; }
    public String getMediaId() { return mediaId; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public String getArtworkUri() { return artworkUri; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(hasMedia);
        dest.writeBoolean(playing);
        dest.writeInt(playbackState);
        dest.writeLong(positionMs);
        dest.writeLong(durationMs);
        dest.writeString(mediaId);
        dest.writeString(title);
        dest.writeString(artist);
        dest.writeString(album);
        dest.writeString(artworkUri);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<MediaPlaybackSnapshot> CREATOR =
            new Creator<MediaPlaybackSnapshot>() {
                @Override
                public MediaPlaybackSnapshot createFromParcel(Parcel in) {
                    return new MediaPlaybackSnapshot(in);
                }

                @Override
                public MediaPlaybackSnapshot[] newArray(int size) {
                    return new MediaPlaybackSnapshot[size];
                }
            };
}
