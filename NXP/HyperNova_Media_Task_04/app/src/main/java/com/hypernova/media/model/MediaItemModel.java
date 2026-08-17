package com.hypernova.media.model;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/** Immutable metadata discovered from a real stream, MediaStore row, or SAF document. */
public final class MediaItemModel {
    private final String id;
    private final Uri uri;
    private final String title;
    private final String artist;
    private final String album;
    private final String genre;
    private final String folder;
    private final String mimeType;
    private final Uri artworkUri;
    private final long durationMs;
    private final boolean video;

    public MediaItemModel(@NonNull String id, @NonNull Uri uri, @NonNull String title,
            @Nullable String artist, @Nullable String album, @Nullable String genre,
            @Nullable String folder, @Nullable String mimeType, @Nullable Uri artworkUri,
            long durationMs, boolean video) {
        this.id = id;
        this.uri = uri;
        this.title = title;
        this.artist = clean(artist);
        this.album = clean(album);
        this.genre = clean(genre);
        this.folder = clean(folder);
        this.mimeType = clean(mimeType);
        this.artworkUri = artworkUri;
        this.durationMs = Math.max(0L, durationMs);
        this.video = video;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public String getId() { return id; }
    public Uri getUri() { return uri; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public String getGenre() { return genre; }
    public String getFolder() { return folder; }
    public String getMimeType() { return mimeType; }
    public Uri getArtworkUri() { return artworkUri; }
    public long getDurationMs() { return durationMs; }
    public boolean isVideo() { return video; }

    public String secondaryText() {
        if (!artist.isEmpty() && !album.isEmpty()) return artist + " · " + album;
        if (!artist.isEmpty()) return artist;
        if (!album.isEmpty()) return album;
        if (!folder.isEmpty()) return folder;
        return video ? "Video" : "Audio";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MediaItemModel && id.equals(((MediaItemModel) other).id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
