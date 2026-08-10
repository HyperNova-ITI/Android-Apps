package com.hypernova.media.usb;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.util.List;

/** Owns one persisted SAF tree and truthfully classifies primary storage as Local Folder. */
public final class UsbPermissionRepository {
    private static final String PREFS = "hypernova_storage";
    private static final String KEY_TREE = "selected_tree";
    private final Context context;

    public UsbPermissionRepository(Context context) { this.context = context.getApplicationContext(); }

    public boolean persist(Uri tree) {
        try {
            context.getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_TREE, tree.toString()).apply();
            return true;
        } catch (SecurityException error) { return false; }
    }

    @Nullable public Uri selectedTree() {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TREE, null);
        if (raw == null) return null;
        Uri uri = Uri.parse(raw);
        for (android.content.UriPermission permission
                : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission() && permission.getUri().equals(uri)) return uri;
        }
        return uri; // Retain the choice so the UI can report revoked access and reconnect.
    }

    public boolean hasReadPermission(Uri tree) {
        for (android.content.UriPermission permission
                : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission() && permission.getUri().equals(tree)) return true;
        }
        return false;
    }

    public String displayName(Uri tree) {
        DocumentFile root = DocumentFile.fromTreeUri(context, tree);
        return root == null || root.getName() == null ? "Selected folder" : root.getName();
    }

    public boolean isRemovableTree(Uri tree, List<UsbStorageMonitor.Volume> volumes) {
        if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) return false;
        String root;
        try { root = DocumentsContract.getTreeDocumentId(tree).split(":", 2)[0]; }
        catch (Exception error) { return false; }
        if ("primary".equalsIgnoreCase(root) || "home".equalsIgnoreCase(root)) return false;
        for (UsbStorageMonitor.Volume volume : volumes) {
            if (root.equalsIgnoreCase(volume.id) || root.equalsIgnoreCase(volume.mediaStoreName)) return true;
        }
        // Android's external-storage provider uses the removable volume UUID as this root id.
        return root.matches("(?i)[0-9a-f]{4}-[0-9a-f]{4}");
    }

    public void forget() {
        Uri tree = selectedTree();
        if (tree != null) {
            try { context.getContentResolver().releasePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) {}
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TREE).apply();
    }
}
