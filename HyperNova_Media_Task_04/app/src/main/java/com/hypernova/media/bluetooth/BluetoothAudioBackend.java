package com.hypernova.media.bluetooth;

import com.hypernova.media.model.BluetoothUiState;

public interface BluetoothAudioBackend {
    interface Listener { void onBluetoothStateChanged(BluetoothUiState state); }
    BluetoothUiState currentState();
    void start(Listener listener);
    void stop(Listener listener);
    void refresh();
}
