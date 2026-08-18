package com.hypernova.media.library;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import com.hypernova.media.model.LibraryUiState;
import com.hypernova.media.model.MediaItemModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MediaStoreRepository {
    public interface Callback { void onState(LibraryUiState state); }
    private static final String TAG = "HyperNovaMediaStore";
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public MediaStoreRepository(Context context) { this.context = context.getApplicationContext(); }

    public void scan(Callback callback) {
        callback.onState(new LibraryUiState(LibraryUiState.Status.SCANNING, "Device media",
                "Reading the Android media library…", Collections.emptyList()));
        executor.execute(() -> {
            try {
                List<MediaItemModel> items = new ArrayList<>();
                boolean legacy = Build.VERSION.SDK_INT < 33 && ContextCompat.checkSelfPermission(context,
                        Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
                boolean audioAllowed = legacy || Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
                boolean videoAllowed = legacy || Build.VERSION.SDK_INT >= 33 && (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                        || Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED);
                if (!audioAllowed && !videoAllowed) throw new SecurityException("No media permission");
                Set<String> volumes = new LinkedHashSet<>();
                if (Build.VERSION.SDK_INT >= 30) volumes.addAll(MediaStore.getExternalVolumeNames(context));
                else volumes.add(MediaStore.VOLUME_EXTERNAL);
                for (String volume : volumes) {
                    if (audioAllowed) query(items, MediaStore.Audio.Media.getContentUri(volume), false);
                    if (videoAllowed) query(items, MediaStore.Video.Media.getContentUri(volume), true);
                }
                items.sort(Comparator.comparing(MediaItemModel::getTitle,
                        String.CASE_INSENSITIVE_ORDER));
                LibraryUiState state = new LibraryUiState(items.isEmpty()
                        ? LibraryUiState.Status.EMPTY : LibraryUiState.Status.READY,
                        "Device media", items.isEmpty() ? "No indexed audio or video was found."
                        : items.size() + " indexed media items", items);
                main.post(() -> callback.onState(state));
            } catch (SecurityException error) {
                main.post(() -> callback.onState(new LibraryUiState(
                        LibraryUiState.Status.PERMISSION_REQUIRED, "Device media",
                        "Allow audio and video access to browse the device library.",
                        Collections.emptyList())));
            } catch (RuntimeException error) {
                Log.e(TAG, "MediaStore scan failed", error);
                main.post(() -> callback.onState(new LibraryUiState(LibraryUiState.Status.ERROR,
                        "Device media", "Android's media index could not be read.",
                        Collections.emptyList())));
            }
        });
    }

    private void query(List<MediaItemModel> output, Uri collection, boolean video) {
        String folderColumn = Build.VERSION.SDK_INT >= 29
                ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.BUCKET_DISPLAY_NAME;
        boolean hasGenreColumn = !video && Build.VERSION.SDK_INT >= 30;
        List<String> projectionValues = new ArrayList<>();
        Collections.addAll(projectionValues, MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.TITLE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DURATION, folderColumn,
                video ? null : MediaStore.Audio.Media.ARTIST,
                video ? null : MediaStore.Audio.Media.ALBUM);
        projectionValues.removeAll(Collections.singleton(null));
        if (hasGenreColumn) projectionValues.add(MediaStore.Audio.Media.GENRE);
        String[] projection = projectionValues.toArray(new String[0]);
        try (Cursor cursor = context.getContentResolver().query(collection, projection,
                null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return;
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE);
            int mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION);
            int pathColumn = cursor.getColumnIndexOrThrow(folderColumn);
            int artistColumn = video ? -1 : cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumColumn = video ? -1 : cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int genreColumn = hasGenreColumn ? cursor.getColumnIndex(MediaStore.Audio.Media.GENRE) : -1;
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                Uri uri = ContentUris.withAppendedId(collection, id);
                String name = cursor.getString(nameColumn);
                String title = cursor.getString(titleColumn);
                String mime = cursor.getString(mimeColumn);
                long duration = cursor.getLong(durationColumn);
                String displayTitle = title == null || title.trim().isEmpty()
                        ? name == null ? "Untitled media" : name : title;
                if (video) {
                    output.add(MediaMetadataReader.read(context, uri, displayTitle, mime,
                            cursor.getString(pathColumn)));
                } else {
                    output.add(new MediaItemModel(uri.toString(), uri, displayTitle,
                            cursor.getString(artistColumn), cursor.getString(albumColumn),
                            genreColumn >= 0 ? cursor.getString(genreColumn) : "",
                            cursor.getString(pathColumn), mime, null, duration, false));
                }
            }
        }
    }
}
