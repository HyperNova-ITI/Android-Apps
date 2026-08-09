package com.hypernova.media.usb;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;

import com.hypernova.media.library.LocalMediaBackend;
import com.hypernova.media.model.LibraryUiState;

import java.util.Collections;
import java.util.List;

/** Public-API USB backend: removable-volume discovery, MediaStore scan, then SAF fallback. */
public final class UsbVolumeRepository implements LocalMediaBackend,
        UsbStorageMonitor.Listener, UsbMediaScanner.Callback {
    private static final String TAG = "HyperNovaUsb";
    private final Context context;
    private final UsbStorageMonitor monitor;
    private final UsbPermissionRepository permissions;
    private final UsbMediaScanner scanner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LibraryUiState state = LibraryUiState.noFolder();
    private UsbUiState usbState = new UsbUiState(UsbUiState.Status.NO_USB, "", "No USB",
            state.message, false, 0, 0, Collections.emptyList(), Collections.emptyList());
    private List<UsbStorageMonitor.Volume> volumes = Collections.emptyList();
    @Nullable private UsbStorageMonitor.Volume selectedVolume;
    @Nullable private Listener listener;
    private boolean started;
    private boolean scanningSaf;
    private boolean activeTreeRemovable;
    private String pendingLabel = "";

    public UsbVolumeRepository(Context context) {
        this.context = context.getApplicationContext();
        permissions = new UsbPermissionRepository(this.context);
        scanner = new UsbMediaScanner(this.context);
        monitor = new UsbStorageMonitor(this.context, this);
    }

    @Override public void start() {
        if (!started) { started = true; monitor.start(); }
        else refreshVolumes();
    }

    @Override public LibraryUiState currentState() { return state; }
    public UsbUiState currentUsbState() { return usbState; }
    public List<UsbStorageMonitor.Volume> availableVolumes() { return volumes; }
    @Override public void setListener(Listener listener) {
        this.listener = listener; listener.onLibraryStateChanged(state);
    }
    @Override public void clearListener(Listener listener) {
        if (this.listener == listener) this.listener = null;
    }

    @Override public void refreshVolumes() { monitor.refresh("resume"); }

    @Override public void onVolumesChanged(List<UsbStorageMonitor.Volume> values, String reason) {
        UsbStorageMonitor.Volume previous = selectedVolume;
        volumes = values;
        if (previous != null && find(previous.id) == null) {
            selectedVolume = null;
            scanner.cancel();
            scanningSaf = false;
            publish(UsbUiState.Status.REMOVED, previous.id, previous.name,
                    "USB removed · playback and stale media are being cleared.", true,
                    0, 0, Collections.emptyList());
            main.postDelayed(this::chooseAvailableSource, 1300L);
            return;
        }
        if (previous != null) selectedVolume = find(previous.id);
        if (selectedVolume == null && values.size() == 1) selectedVolume = values.get(0);
        Uri retained = permissions.selectedTree();
        if (selectedVolume == null && values.isEmpty() && retained != null
                && state.status == LibraryUiState.Status.READY
                && retained.toString().equals(state.volumeId)
                && !android.content.Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(reason)) return;
        chooseAvailableSource();
    }

    private void chooseAvailableSource() {
        if (selectedVolume != null) {
            scanVolume(selectedVolume);
            return;
        }
        if (volumes.size() > 1) {
            publish(UsbUiState.Status.MULTIPLE_VOLUMES, "", "Multiple USB volumes",
                    "Choose which mounted removable volume to browse.", true,
                    0, 0, Collections.emptyList());
            return;
        }
        Uri tree = permissions.selectedTree();
        if (tree != null) {
            if (!permissions.hasReadPermission(tree)) {
                publish(UsbUiState.Status.PERMISSION_REQUIRED, "", "USB permission required",
                        "Stored folder access was revoked. Reconnect through Android's folder picker.",
                        false, 0, 0, Collections.emptyList());
            } else scanTree(tree);
            return;
        }
        publish(UsbUiState.Status.NO_USB, "", "No USB",
                "Connect a USB/OTG drive. If Android does not expose it automatically, select its folder.",
                false, 0, 0, Collections.emptyList());
    }

    public void selectVolume(String id) {
        UsbStorageMonitor.Volume match = find(id);
        if (match != null) { selectedVolume = match; scanVolume(match); }
    }

    @Nullable private UsbStorageMonitor.Volume find(String id) {
        for (UsbStorageMonitor.Volume volume : volumes) if (volume.id.equals(id)) return volume;
        return null;
    }

    private void scanVolume(UsbStorageMonitor.Volume volume) {
        if (!volume.mounted()) return;
        pendingLabel = volume.name;
        scanningSaf = false;
        publish(UsbUiState.Status.SCANNING, volume.id, volume.name,
                "Mounted · scanning Android's media index…", true, 0, 0,
                Collections.emptyList());
        if (Build.VERSION.SDK_INT < 30 || volume.mediaStoreName.isEmpty()
                || !MediaStore.getExternalVolumeNames(context).contains(volume.mediaStoreName)) {
            scanner.cancel();
            publish(UsbUiState.Status.PERMISSION_REQUIRED, volume.id, volume.name,
                    "Android mounted this USB but did not expose a MediaStore volume. Select its folder to continue.",
                    true, 0, 0, Collections.emptyList());
            return;
        }
        scanner.scanMediaStore(volume.mediaStoreName, volume.id, this);
    }

    @Override public void selectTree(Uri treeUri) {
        if (!permissions.persist(treeUri)) {
            publish(UsbUiState.Status.PERMISSION_REQUIRED, "", "USB permission required",
                    "Android did not grant persistent read access.", false, 0, 0,
                    Collections.emptyList());
            return;
        }
        selectedVolume = null;
        scanTree(treeUri);
    }

    private void scanTree(Uri tree) {
        activeTreeRemovable = permissions.isRemovableTree(tree, volumes);
        scanningSaf = true;
        pendingLabel = permissions.displayName(tree);
        String display = activeTreeRemovable ? pendingLabel : "Local Folder · " + pendingLabel;
        publish(UsbUiState.Status.SCANNING, tree.toString(), display,
                activeTreeRemovable ? "USB folder permission active · scanning…"
                        : "Local folder selected · scanning…", activeTreeRemovable,
                0, 0, Collections.emptyList());
        scanner.scanTree(tree, activeTreeRemovable, this);
    }

    @Override public void scanSelectedTree() {
        Uri tree = permissions.selectedTree();
        if (tree != null && permissions.hasReadPermission(tree)) scanTree(tree);
        else if (selectedVolume != null) scanVolume(selectedVolume);
        else refreshVolumes();
    }

    @Override public void scanMediaStore() {
        if (selectedVolume != null) scanVolume(selectedVolume);
        else refreshVolumes();
    }

    @Override public void forgetSelectedTree() {
        permissions.forget();
        if (selectedVolume == null) chooseAvailableSource();
    }

    @Override public void onComplete(UsbMediaScanner.Result result) {
        boolean removable = selectedVolume != null || activeTreeRemovable;
        String id = selectedVolume == null ? permissions.selectedTree() == null ? ""
                : permissions.selectedTree().toString() : selectedVolume.id;
        String label = selectedVolume == null && !removable ? "Local Folder · " + pendingLabel : pendingLabel;
        UsbUiState.Status status = result.items.isEmpty()
                ? result.unsupportedCount > 0 ? UsbUiState.Status.UNSUPPORTED : UsbUiState.Status.EMPTY
                : removable ? UsbUiState.Status.READY : UsbUiState.Status.LOCAL_FOLDER;
        String detail;
        if (result.items.isEmpty()) detail = result.unsupportedCount > 0
                ? result.unsupportedCount + " files found, but none are supported audio or video."
                : "No supported audio or video was found.";
        else detail = result.audioCount + " tracks · " + result.videoCount + " videos"
                + (result.truncated ? " · scan limit reached" : "");
        publish(status, id, label, detail, removable, result.audioCount,
                result.videoCount, result.items);
    }

    @Override public void onError(String message, boolean permission) {
        Log.w(TAG, message);
        publish(permission ? UsbUiState.Status.PERMISSION_REQUIRED : UsbUiState.Status.READ_ERROR,
                selectedVolume == null ? "" : selectedVolume.id,
                pendingLabel.isEmpty() ? "Selected storage" : pendingLabel, message,
                selectedVolume != null || activeTreeRemovable, 0, 0, Collections.emptyList());
    }

    private void publish(UsbUiState.Status status, String id, String label, String detail,
            boolean removable, int audio, int video,
            List<com.hypernova.media.model.MediaItemModel> items) {
        usbState = new UsbUiState(status, id, label, detail, removable, audio, video,
                items, volumes);
        LibraryUiState.Status legacy;
        switch (status) {
            case NO_USB: legacy = LibraryUiState.Status.NO_USB; break;
            case DETECTED: legacy = LibraryUiState.Status.DETECTED; break;
            case MULTIPLE_VOLUMES: legacy = LibraryUiState.Status.MULTIPLE_VOLUMES; break;
            case PERMISSION_REQUIRED: legacy = LibraryUiState.Status.PERMISSION_REQUIRED; break;
            case SCANNING: legacy = LibraryUiState.Status.SCANNING; break;
            case READY: case LOCAL_FOLDER: legacy = LibraryUiState.Status.READY; break;
            case EMPTY: legacy = LibraryUiState.Status.EMPTY; break;
            case UNSUPPORTED: legacy = LibraryUiState.Status.UNSUPPORTED; break;
            case REMOVED: legacy = LibraryUiState.Status.REMOVED; break;
            default: legacy = LibraryUiState.Status.ERROR;
        }
        state = new LibraryUiState(legacy, label, detail, items, audio, video, removable, id);
        if (listener != null) listener.onLibraryStateChanged(state);
    }
}
