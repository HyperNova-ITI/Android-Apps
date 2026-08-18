package com.hypernova.media.usb;

import com.hypernova.media.model.MediaItemModel;

import java.util.Collections;
import java.util.List;

/** Detailed immutable USB snapshot retained below the existing UI compatibility state. */
public final class UsbUiState {
    public enum Status {
        NO_USB, DETECTED, MULTIPLE_VOLUMES, PERMISSION_REQUIRED, SCANNING,
        READY, EMPTY, UNSUPPORTED, REMOVED, READ_ERROR, LOCAL_FOLDER
    }
    public final Status status;
    public final String volumeId;
    public final String displayName;
    public final String detail;
    public final boolean removable;
    public final int audioCount;
    public final int videoCount;
    public final List<MediaItemModel> items;
    public final List<UsbStorageMonitor.Volume> volumes;

    public UsbUiState(Status status, String volumeId, String displayName, String detail,
            boolean removable, int audioCount, int videoCount, List<MediaItemModel> items,
            List<UsbStorageMonitor.Volume> volumes) {
        this.status = status;
        this.volumeId = clean(volumeId);
        this.displayName = clean(displayName);
        this.detail = clean(detail);
        this.removable = removable;
        this.audioCount = Math.max(0, audioCount);
        this.videoCount = Math.max(0, videoCount);
        this.items = Collections.unmodifiableList(items);
        this.volumes = Collections.unmodifiableList(volumes);
    }

    private static String clean(String value) { return value == null ? "" : value; }
}
