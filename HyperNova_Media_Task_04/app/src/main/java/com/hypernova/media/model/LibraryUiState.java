package com.hypernova.media.model;

import java.util.Collections;
import java.util.List;

public final class LibraryUiState {
    public enum Status {
        PERMISSION_REQUIRED, NO_FOLDER, NO_USB, DETECTED, MULTIPLE_VOLUMES,
        SCANNING, READY, EMPTY, UNSUPPORTED, REMOVED, ERROR
    }

    public final Status status;
    public final String sourceLabel;
    public final String message;
    public final List<MediaItemModel> items;
    public final int audioCount;
    public final int videoCount;
    public final boolean removable;
    public final String volumeId;

    public LibraryUiState(Status status, String sourceLabel, String message,
            List<MediaItemModel> items) {
        this.status = status;
        this.sourceLabel = sourceLabel == null ? "" : sourceLabel;
        this.message = message == null ? "" : message;
        this.items = Collections.unmodifiableList(items);
        int audio = 0;
        for (MediaItemModel item : items) if (!item.isVideo()) audio++;
        this.audioCount = audio;
        this.videoCount = items.size() - audio;
        this.removable = false;
        this.volumeId = "";
    }

    public LibraryUiState(Status status, String sourceLabel, String message,
            List<MediaItemModel> items, int audioCount, int videoCount,
            boolean removable, String volumeId) {
        this.status = status;
        this.sourceLabel = sourceLabel == null ? "" : sourceLabel;
        this.message = message == null ? "" : message;
        this.items = Collections.unmodifiableList(items);
        this.audioCount = Math.max(0, audioCount);
        this.videoCount = Math.max(0, videoCount);
        this.removable = removable;
        this.volumeId = volumeId == null ? "" : volumeId;
    }

    public static LibraryUiState noFolder() {
        return new LibraryUiState(Status.NO_USB, "No USB",
                "Connect a mounted USB/OTG drive or select its folder with Android's picker.",
                Collections.emptyList());
    }
}
