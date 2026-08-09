package com.hypernova.media.model;

import java.util.Collections;
import java.util.List;

public final class BluetoothUiState {
    public enum Status {
        UNSUPPORTED,
        PERMISSION_REQUIRED,
        OFF,
        ON_NO_PAIRED_AUDIO,
        PAIRED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    public final Status status;
    public final String activeDeviceName;
    public final List<String> pairedAudioDevices;
    public final String detail;

    public final String trackTitle;
    public final String artist;
    public final String album;
    public final boolean remotePlaying;
    public final long remotePositionMs;
    public final long remoteDurationMs;

    public BluetoothUiState(
            Status status,
            String activeDeviceName,
            List<String> pairedAudioDevices,
            String detail) {
        this(
                status,
                activeDeviceName,
                pairedAudioDevices,
                detail,
                "",
                "",
                "",
                false,
                0L,
                0L);
    }

    public BluetoothUiState(
            Status status,
            String activeDeviceName,
            List<String> pairedAudioDevices,
            String detail,
            String trackTitle,
            String artist,
            String album,
            boolean remotePlaying,
            long remotePositionMs,
            long remoteDurationMs) {
        this.status = status;
        this.activeDeviceName =
                activeDeviceName == null ? "" : activeDeviceName;

        this.pairedAudioDevices =
                Collections.unmodifiableList(pairedAudioDevices);

        this.detail = detail == null ? "" : detail;
        this.trackTitle = trackTitle == null ? "" : trackTitle;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.remotePlaying = remotePlaying;
        this.remotePositionMs = Math.max(0L, remotePositionMs);
        this.remoteDurationMs = Math.max(0L, remoteDurationMs);
    }

    public boolean isConnected() {
        return status == Status.CONNECTED;
    }

    public boolean hasRemoteMedia() {
        return !trackTitle.trim().isEmpty();
    }

    public String remoteSecondaryText() {
        StringBuilder value = new StringBuilder();

        if (!artist.isEmpty()) {
            value.append(artist);
        }

        if (!album.isEmpty() && !album.equalsIgnoreCase(trackTitle)) {
            if (value.length() > 0) {
                value.append(" · ");
            }

            value.append(album);
        }

        if (!activeDeviceName.isEmpty()) {
            if (value.length() > 0) {
                value.append(" · ");
            }

            value.append(activeDeviceName);
        }

        return value.toString();
    }
}
