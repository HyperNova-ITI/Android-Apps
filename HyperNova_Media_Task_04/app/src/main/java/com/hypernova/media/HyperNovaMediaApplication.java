package com.hypernova.media;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.hypernova.media.playback.PlaybackController;
import com.hypernova.media.bluetooth.PhoneBluetoothAudioBackend;
import com.hypernova.media.usb.UsbVolumeRepository;
import com.hypernova.media.radio.InternetRadioBackend;
import com.hypernova.media.radio.RadioRepository;

public final class HyperNovaMediaApplication extends Application {
    private PlaybackController playbackController;
    private RadioRepository radioStations;
    private InternetRadioBackend radioBackend;
    private PhoneBluetoothAudioBackend bluetoothBackend;
    private UsbVolumeRepository localMediaBackend;

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        playbackController = new PlaybackController(this);
        radioStations = new RadioRepository(this);
        radioBackend = new InternetRadioBackend(radioStations, playbackController);
        bluetoothBackend = new PhoneBluetoothAudioBackend(this);
        localMediaBackend = new UsbVolumeRepository(this);
        localMediaBackend.start();
    }

    public PlaybackController playback() { return playbackController; }
    public RadioRepository radioStations() { return radioStations; }
    public InternetRadioBackend radio() { return radioBackend; }
    public PhoneBluetoothAudioBackend bluetooth() { return bluetoothBackend; }
    public UsbVolumeRepository library() { return localMediaBackend; }
}
