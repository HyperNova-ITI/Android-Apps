package com.hypernova.media;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.hypernova.media.playback.PlaybackController;
import com.hypernova.media.bluetooth.PhoneBluetoothAudioBackend;
import com.hypernova.media.audio.MediaVolumeController;
import com.hypernova.media.radio.InternetRadioBackend;
import com.hypernova.media.radio.RadioRepository;
import com.hypernova.media.video.YoutubeWebSession;

public final class HyperNovaMediaApplication extends Application {
    private PlaybackController playbackController;
    private MediaVolumeController volumeController;
    private RadioRepository radioStations;
    private InternetRadioBackend radioBackend;
    private PhoneBluetoothAudioBackend bluetoothBackend;
    private YoutubeWebSession youtubeWebSession;

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        playbackController = new PlaybackController(this);
        volumeController = new MediaVolumeController(this);
        radioStations = new RadioRepository(this);
        radioBackend = new InternetRadioBackend(radioStations, playbackController);
        bluetoothBackend = new PhoneBluetoothAudioBackend(this);
        youtubeWebSession = new YoutubeWebSession();
    }

    public PlaybackController playback() { return playbackController; }
    public MediaVolumeController volume() { return volumeController; }
    public RadioRepository radioStations() { return radioStations; }
    public InternetRadioBackend radio() { return radioBackend; }
    public PhoneBluetoothAudioBackend bluetooth() { return bluetoothBackend; }
    public YoutubeWebSession youtubeWeb() { return youtubeWebSession; }
}
