package com.hypernova.media.library;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.hypernova.media.model.LibraryUiState;
import com.hypernova.media.model.MediaItemModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded, off-main-thread SAF traversal suitable for local folders and removable OTG media. */
public final class SafUsbRepository {
    public interface Callback { void onState(LibraryUiState state); }
    private static final String TAG = "HyperNovaStorage";
    private static final String KEY_TREE = "selected_tree";
    private static final int MAX_FILES = 2500;
    private static final int MAX_DEPTH = 12;
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private Callback callback;

    public SafUsbRepository(Context context) { this.context = context.getApplicationContext(); }
    public void setCallback(Callback callback) { this.callback = callback; }

    @Nullable public Uri selectedTree() {
        String raw = context.getSharedPreferences("hypernova_storage", Context.MODE_PRIVATE)
                .getString(KEY_TREE, null);
        return raw == null ? null : Uri.parse(raw);
    }

    public void persistTree(Uri treeUri) {
        try {
            context.getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException writeDenied) {
            try {
                context.getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException readDenied) {
                publish(new LibraryUiState(LibraryUiState.Status.ERROR, "Selected folder",
                        "Android did not grant persistent read access to this folder.",
                        Collections.emptyList()));
                return;
            }
        }
        context.getSharedPreferences("hypernova_storage", Context.MODE_PRIVATE).edit()
                .putString(KEY_TREE, treeUri.toString()).apply();
        scan();
    }

    public void scan() {
        Uri treeUri = selectedTree();
        if (treeUri == null) {
            publish(LibraryUiState.noFolder());
            return;
        }
        publish(new LibraryUiState(LibraryUiState.Status.SCANNING, "Selected folder",
                "Scanning audio and video…", Collections.emptyList()));
        executor.execute(() -> scanBlocking(treeUri));
    }

    private void scanBlocking(Uri treeUri) {
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.exists() || !root.canRead()) {
            publish(new LibraryUiState(LibraryUiState.Status.ERROR, "Selected folder",
                    "Folder access was revoked or the storage device was removed.",
                    Collections.emptyList()));
            return;
        }
        String label = root.getName() == null ? "Selected folder" : root.getName();
        List<MediaItemModel> result = new ArrayList<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.add(new Node(root, 0, label));
        try {
            while (!pending.isEmpty() && result.size() < MAX_FILES) {
                Node node = pending.removeFirst();
                DocumentFile[] children = node.file.listFiles();
                for (DocumentFile child : children) {
                    if (result.size() >= MAX_FILES) break;
                    if (child.isDirectory() && node.depth < MAX_DEPTH) {
                        String name = child.getName() == null ? "Folder" : child.getName();
                        pending.addLast(new Node(child, node.depth + 1, name));
                    } else if (child.isFile() && isSupported(child.getType(), child.getName())) {
                        String name = child.getName() == null ? "Untitled media" : child.getName();
                        String mime = normalizeMime(child.getType(), name);
                        result.add(MediaMetadataReader.read(context, child.getUri(), name, mime,
                                node.folder));
                    }
                }
            }
            result.sort(Comparator.comparing(MediaItemModel::getTitle,
                    String.CASE_INSENSITIVE_ORDER));
            String detail = result.isEmpty()
                    ? "No supported audio or video was found."
                    : result.size() + " media items" + (result.size() >= MAX_FILES ? " · scan limit reached" : "");
            publish(new LibraryUiState(result.isEmpty() ? LibraryUiState.Status.EMPTY
                    : LibraryUiState.Status.READY, label, detail, result));
        } catch (SecurityException error) {
            Log.w(TAG, "SAF permission revoked during scan", error);
            publish(new LibraryUiState(LibraryUiState.Status.ERROR, label,
                    "Folder access was revoked or the device was removed.",
                    Collections.emptyList()));
        } catch (RuntimeException error) {
            Log.e(TAG, "SAF scan failed", error);
            publish(new LibraryUiState(LibraryUiState.Status.ERROR, label,
                    "The selected storage could not be scanned.", Collections.emptyList()));
        }
    }

    private boolean isSupported(String mime, String name) {
        String normalized = normalizeMime(mime, name);
        return normalized.startsWith("audio/") || normalized.startsWith("video/");
    }

    private String normalizeMime(String mime, String name) {
        if (mime != null && !"application/octet-stream".equals(mime)) return mime;
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.(mp4|mkv|webm|3gp|m4v)$")) return "video/*";
        if (lower.matches(".*\\.(mp3|m4a|aac|wav|flac|ogg|opus)$")) return "audio/*";
        return "application/octet-stream";
    }

    private void publish(LibraryUiState state) {
        main.post(() -> { if (callback != null) callback.onState(state); });
    }

    private static final class Node {
        final DocumentFile file; final int depth; final String folder;
        Node(DocumentFile file, int depth, String folder) {
            this.file = file; this.depth = depth; this.folder = folder;
        }
    }
}
