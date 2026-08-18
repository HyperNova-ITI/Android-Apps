package com.hypernova.media.library;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.hypernova.media.model.MediaItemModel;

public final class MediaMetadataReader {
    private MediaMetadataReader() {}

    public static MediaItemModel read(Context context, Uri uri, String displayName, String mimeType,
            String folder) {
        return read(context, uri.toString(), uri, displayName, mimeType, folder);
    }

    public static MediaItemModel read(Context context, String id, Uri uri, String displayName,
            String mimeType, String folder) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        String title = stripExtension(displayName);
        String artist = "";
        String album = "";
        String genre = "";
        long duration = 0L;
        try {
            retriever.setDataSource(context, uri);
            title = first(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), title);
            artist = first(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), "");
            album = first(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM), "");
            genre = first(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE), "");
            String rawDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (rawDuration != null) duration = Long.parseLong(rawDuration);
        } catch (Exception ignored) {
            // A playable URI can have sparse metadata; playback will report codec/stream errors.
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
        boolean video = mimeType != null && mimeType.startsWith("video/");
        return new MediaItemModel(id, uri, title, artist, album, genre, folder,
                mimeType, null, duration, video);
    }

    private static String first(String candidate, String fallback) {
        return candidate == null || candidate.trim().isEmpty() ? fallback : candidate.trim();
    }

    private static String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }
}
