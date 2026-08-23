package com.hypernova.media;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.hypernova.media.playback.PlaybackController;
import com.hypernova.media.bluetooth.PhoneBluetoothAudioBackend;
import com.hypernova.media.radio.InternetRadioBackend;
import com.hypernova.media.radio.RadioRepository;
import com.hypernova.media.video.YoutubeWebSession;

public final class HyperNovaMediaApplication extends Application {
    private PlaybackController playbackController;
    private RadioRepository radioStations;
    private InternetRadioBackend radioBackend;
    private PhoneBluetoothAudioBackend bluetoothBackend;
    private YoutubeWebSession youtubeWebSession;

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    /**
     * Launcher connects directly to HyperNovaPlaybackService for its media card. Keep every
     * Activity-only controller lazy so starting that service does not also create a second
     * MediaController, radio stack, Bluetooth client and progress ticker in Application.onCreate.
     */
    public synchronized PlaybackController playback() {
        if (playbackController == null) playbackController = new PlaybackController(this);
        return playbackController;
    }

    public synchronized RadioRepository radioStations() {
        if (radioStations == null) radioStations = new RadioRepository(this);
        return radioStations;
    }

    public synchronized InternetRadioBackend radio() {
        if (radioBackend == null) {
            radioBackend = new InternetRadioBackend(radioStations(), playback());
        }
        return radioBackend;
    }

    public synchronized PhoneBluetoothAudioBackend bluetooth() {
        if (bluetoothBackend == null) bluetoothBackend = new PhoneBluetoothAudioBackend(this);
        return bluetoothBackend;
    }

    public synchronized YoutubeWebSession youtubeWeb() {
        if (youtubeWebSession == null) youtubeWebSession = new YoutubeWebSession();
        return youtubeWebSession;
    }
}
