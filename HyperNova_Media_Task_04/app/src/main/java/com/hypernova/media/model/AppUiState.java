package com.hypernova.media.model;

public final class AppUiState {
    public final MediaSourceType source;
    public final PlaybackUiState playback;
    public final BluetoothUiState bluetooth;
    public final LibraryUiState library;
    public final boolean demo;

    public AppUiState(MediaSourceType source, PlaybackUiState playback,
            BluetoothUiState bluetooth, LibraryUiState library, boolean demo) {
        this.source = source;
        this.playback = playback;
        this.bluetooth = bluetooth;
        this.library = library;
        this.demo = demo;
    }
}
