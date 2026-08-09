package com.hypernova.media.usb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Process-lifetime public-API observer for mounted non-emulated removable volumes. */
public final class UsbStorageMonitor {
    public interface Listener { void onVolumesChanged(List<Volume> volumes, String reason); }
    public static final class Volume {
        public final String id;
        public final String name;
        public final String mediaStoreName;
        public final String state;
        public final boolean removable;
        public final boolean emulated;
        public final File directory;

        Volume(String id, String name, String mediaStoreName, String state,
                boolean removable, boolean emulated, File directory) {
            this.id = id; this.name = name; this.mediaStoreName = mediaStoreName;
            this.state = state; this.removable = removable; this.emulated = emulated;
            this.directory = directory;
        }
        public boolean mounted() {
            return Environment.MEDIA_MOUNTED.equals(state)
                    || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
        }
    }

    private static final String TAG = "HyperNovaUsbMonitor";
    private final Context context;
    private final StorageManager storageManager;
    private final Listener listener;
    private boolean started;
    private String fingerprint = "";
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refresh(intent.getAction() == null ? "storage broadcast" : intent.getAction());
        }
    };

    public UsbStorageMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        storageManager = (StorageManager) this.context.getSystemService(Context.STORAGE_SERVICE);
    }

    public void start() {
        if (started) { refresh("resume"); return; }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        filter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        filter.addDataScheme("file");
        // Media mount events originate outside this application. Every callback re-queries
        // StorageManager, so a spoofed unprotected scanner event cannot inject a volume.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        started = true;
        refresh("startup");
    }

    public void refresh(String reason) {
        List<Volume> values = query();
        StringBuilder next = new StringBuilder();
        for (Volume value : values) next.append(value.id).append(':').append(value.state).append('|');
        boolean changed = !fingerprint.contentEquals(next);
        fingerprint = next.toString();
        Log.i(TAG, reason + " · mounted removable volumes=" + values.size());
        if (changed || "startup".equals(reason) || "resume".equals(reason)
                || Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(reason)) {
            listener.onVolumesChanged(Collections.unmodifiableList(values), reason);
        }
    }

    public List<Volume> query() {
        if (storageManager == null) return Collections.emptyList();
        List<Volume> result = new ArrayList<>();
        try {
            for (StorageVolume volume : storageManager.getStorageVolumes()) {
                boolean removable = volume.isRemovable();
                boolean emulated = volume.isEmulated();
                if (!removable || emulated) continue;
                String uuid = volume.getUuid();
                String mediaName = Build.VERSION.SDK_INT >= 30 ? volume.getMediaStoreVolumeName() : null;
                File directory = Build.VERSION.SDK_INT >= 30 ? volume.getDirectory() : null;
                String id = uuid != null ? uuid : mediaName != null ? mediaName
                        : directory != null ? directory.getAbsolutePath() : volume.getDescription(context);
                result.add(new Volume(id, volume.getDescription(context), mediaName == null ? "" : mediaName,
                        volume.getState(), removable, emulated, directory));
            }
        } catch (SecurityException error) {
            Log.w(TAG, "Storage volume visibility restricted", error);
        }
        result.removeIf(value -> !value.mounted());
        result.sort(Comparator.comparing(value -> value.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }
}
