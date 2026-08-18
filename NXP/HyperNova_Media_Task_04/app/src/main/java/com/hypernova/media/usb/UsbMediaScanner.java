package com.hypernova.media.usb;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.hypernova.media.library.MediaMetadataReader;
import com.hypernova.media.model.MediaItemModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Cancellable, bounded, single-worker metadata scanner for MediaStore volumes and SAF trees. */
public final class UsbMediaScanner {
    public interface Callback {
        void onComplete(Result result);
        void onError(String message, boolean permission);
    }
    public static final class Result {
        public final List<MediaItemModel> items;
        public final int audioCount;
        public final int videoCount;
        public final int unsupportedCount;
        public final boolean truncated;
        Result(List<MediaItemModel> items, int unsupportedCount, boolean truncated) {
            this.items = Collections.unmodifiableList(items);
            int audio = 0;
            for (MediaItemModel item : items) if (!item.isVideo()) audio++;
            this.audioCount = audio; this.videoCount = items.size() - audio;
            this.unsupportedCount = unsupportedCount; this.truncated = truncated;
        }
    }

    private static final String TAG = "HyperNovaUsbScanner";
    private static final int MAX_FILES = 4000;
    private static final int MAX_DEPTH = 16;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "HyperNova-UsbScanner");
        thread.setDaemon(true); return thread;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private Future<?> active;

    public UsbMediaScanner(Context context) { this.context = context.getApplicationContext(); }

    public synchronized void cancel() {
        generation.incrementAndGet();
        if (active != null) active.cancel(true);
        active = null;
    }

    public synchronized void scanTree(Uri tree, boolean removable, Callback callback) {
        cancel();
        int token = generation.get();
        active = executor.submit(() -> scanTreeBlocking(tree, removable, token, callback));
    }

    public synchronized void scanMediaStore(String volumeName, String volumeId, Callback callback) {
        cancel();
        int token = generation.get();
        active = executor.submit(() -> scanMediaStoreBlocking(volumeName, volumeId, token, callback));
    }

    private void scanTreeBlocking(Uri tree, boolean removable, int token, Callback callback) {
        DocumentFile root = DocumentFile.fromTreeUri(context, tree);
        if (root == null || !root.exists() || !root.canRead()) {
            error(token, callback, "Folder permission was revoked or the storage was removed.", true);
            return;
        }
        String idPrefix = removable ? "usb:saf:" : "local:saf:";
        List<MediaItemModel> items = new ArrayList<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.add(new Node(root, 0, root.getName() == null ? "Selected folder" : root.getName()));
        int unsupported = 0;
        try {
            while (!pending.isEmpty() && items.size() < MAX_FILES && valid(token)) {
                Node node = pending.removeFirst();
                for (DocumentFile child : node.file.listFiles()) {
                    if (!valid(token) || items.size() >= MAX_FILES) break;
                    if (child.isDirectory() && node.depth < MAX_DEPTH) {
                        pending.addLast(new Node(child, node.depth + 1,
                                child.getName() == null ? node.folder : child.getName()));
                    } else if (child.isFile()) {
                        String name = child.getName() == null ? "Untitled media" : child.getName();
                        String mime = normalizeMime(child.getType(), name);
                        if (supported(mime)) items.add(MediaMetadataReader.read(context,
                                idPrefix + child.getUri(), child.getUri(), name, mime, node.folder));
                        else unsupported++;
                    }
                }
            }
            deliver(token, callback, new Result(sort(items), unsupported,
                    items.size() >= MAX_FILES || !pending.isEmpty()));
        } catch (SecurityException error) {
            error(token, callback, "Folder permission was revoked or the USB was removed.", true);
        } catch (RuntimeException error) {
            Log.e(TAG, "SAF scan failed", error);
            error(token, callback, "Android could not read the selected storage.", false);
        }
    }

    private void scanMediaStoreBlocking(String volume, String volumeId, int token, Callback callback) {
        try {
            boolean audioAllowed = audioAllowed();
            boolean videoAllowed = videoAllowed();
            if (!audioAllowed && !videoAllowed) {
                error(token, callback, "Allow audio or video access, or select the USB folder privately.", true);
                return;
            }
            List<MediaItemModel> items = new ArrayList<>();
            if (audioAllowed) query(items, MediaStore.Audio.Media.getContentUri(volume), false,
                    volumeId, token);
            if (videoAllowed && valid(token)) query(items, MediaStore.Video.Media.getContentUri(volume), true,
                    volumeId, token);
            deliver(token, callback, new Result(sort(items), 0, items.size() >= MAX_FILES));
        } catch (SecurityException error) {
            error(token, callback, "Media permission is required to scan this mounted USB.", true);
        } catch (RuntimeException error) {
            Log.e(TAG, "MediaStore volume scan failed", error);
            error(token, callback, "Android's media index could not read this USB.", false);
        }
    }

    private void query(List<MediaItemModel> output, Uri collection, boolean video,
            String volumeId, int token) {
        String folderColumn = Build.VERSION.SDK_INT >= 29
                ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.BUCKET_DISPLAY_NAME;
        List<String> columns = new ArrayList<>();
        Collections.addAll(columns, MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.TITLE, MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DURATION, folderColumn);
        if (!video) { columns.add(MediaStore.Audio.Media.ARTIST); columns.add(MediaStore.Audio.Media.ALBUM); }
        try (Cursor cursor = context.getContentResolver().query(collection,
                columns.toArray(new String[0]), null, null,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return;
            while (cursor.moveToNext() && output.size() < MAX_FILES && valid(token)) {
                Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(index(cursor, MediaStore.MediaColumns._ID)));
                String name = text(cursor, MediaStore.MediaColumns.DISPLAY_NAME);
                String title = text(cursor, MediaStore.MediaColumns.TITLE);
                if (title.isEmpty()) title = name.isEmpty() ? "Untitled media" : name;
                String mime = normalizeMime(text(cursor, MediaStore.MediaColumns.MIME_TYPE), name);
                if (!supported(mime)) continue;
                String folder = text(cursor, folderColumn);
                if (video) output.add(MediaMetadataReader.read(context, "usb:" + volumeId + ":" + uri,
                        uri, title, mime, folder));
                else output.add(new MediaItemModel("usb:" + volumeId + ":" + uri, uri, title,
                        text(cursor, MediaStore.Audio.Media.ARTIST),
                        text(cursor, MediaStore.Audio.Media.ALBUM), "", folder, mime, null,
                        cursor.getLong(index(cursor, MediaStore.MediaColumns.DURATION)), false));
            }
        }
    }

    private boolean audioAllowed() {
        return Build.VERSION.SDK_INT >= 33
                ? ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                : ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
    }

    private boolean videoAllowed() {
        return Build.VERSION.SDK_INT >= 33
                ? ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED
                    || Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
                : ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
    }

    private boolean valid(int token) { return token == generation.get() && !Thread.currentThread().isInterrupted(); }
    private void deliver(int token, Callback callback, Result result) {
        if (valid(token)) main.post(() -> { if (token == generation.get()) callback.onComplete(result); });
    }
    private void error(int token, Callback callback, String message, boolean permission) {
        if (valid(token)) main.post(() -> { if (token == generation.get()) callback.onError(message, permission); });
    }

    private static List<MediaItemModel> sort(List<MediaItemModel> items) {
        items.sort(Comparator.comparing(MediaItemModel::getTitle, String.CASE_INSENSITIVE_ORDER));
        return items;
    }
    private static boolean supported(String mime) {
        return mime.startsWith("audio/") || mime.startsWith("video/");
    }
    private static String normalizeMime(String mime, String name) {
        if (mime != null && (mime.startsWith("audio/") || mime.startsWith("video/"))) return mime;
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.(mp4|mkv|webm|3gp|m4v|ts|mpeg|mpg)$")) return "video/*";
        if (lower.matches(".*\\.(mp3|m4a|aac|wav|flac|ogg|opus|amr|mid|midi)$")) return "audio/*";
        return mime == null ? "application/octet-stream" : mime;
    }
    private static int index(Cursor cursor, String name) { return cursor.getColumnIndexOrThrow(name); }
    private static String text(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        String value = index < 0 ? null : cursor.getString(index);
        return value == null ? "" : value;
    }
    private static final class Node {
        final DocumentFile file; final int depth; final String folder;
        Node(DocumentFile file, int depth, String folder) {
            this.file = file; this.depth = depth; this.folder = folder;
        }
    }
}
