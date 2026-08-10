package com.hypernova.media.model;

import androidx.annotation.NonNull;

import com.hypernova.media.radio.RadioSearchQuery;
import com.hypernova.media.radio.RadioStation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable catalog state rendered by the radio surface. */
public final class RadioUiState {
    public enum Status { LOADING, READY, CACHED, OFFLINE, EMPTY, ERROR }

    public final Status status;
    public final List<RadioStation> stations;
    public final RadioSearchQuery query;
    public final String message;
    public final String server;
    public final long updatedAt;

    public RadioUiState(@NonNull Status status, @NonNull List<RadioStation> stations,
            @NonNull RadioSearchQuery query, String message, String server, long updatedAt) {
        this.status = status;
        this.stations = Collections.unmodifiableList(new ArrayList<>(stations));
        this.query = query;
        this.message = message == null ? "" : message;
        this.server = server == null ? "" : server;
        this.updatedAt = Math.max(0L, updatedAt);
    }

    public boolean hasStations() { return !stations.isEmpty(); }

    public static RadioUiState initial(List<RadioStation> cached) {
        return new RadioUiState(cached.isEmpty() ? Status.LOADING : Status.CACHED, cached,
                RadioSearchQuery.popular(), cached.isEmpty() ? "Finding healthy stations…"
                        : "Showing the last successful catalog while refreshing.", "", 0L);
    }
}
