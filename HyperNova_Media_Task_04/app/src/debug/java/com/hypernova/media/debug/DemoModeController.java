package com.hypernova.media.debug;

import android.content.Intent;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.hypernova.media.R;
import com.hypernova.media.model.MediaSourceType;
import com.hypernova.media.ui.MainUiRenderer;
import com.hypernova.media.ui.PreviewUiState;
import com.hypernova.media.visualizer.VisualizerMode;

/** Debug source-set only: sample review metadata never enters the release APK. */
public final class DemoModeController {
    public interface Listener { void onDemoActiveChanged(boolean active); }
    private final AppCompatActivity activity;
    private final MainUiRenderer renderer;
    private final Listener listener;
    private boolean active;

    public DemoModeController(AppCompatActivity activity, View root, MainUiRenderer renderer,
            Listener listener) {
        this.activity = activity;
        this.renderer = renderer;
        this.listener = listener;
        root.findViewById(R.id.header).setOnLongClickListener(v -> { showMenu(); return true; });
    }

    public boolean isActive() { return active; }
    public void applyIntent(Intent intent) {
        if (intent == null || !intent.hasExtra("demo_state")) return;
        String value = intent.getStringExtra("demo_state");
        if (value == null) return;
        String[] states = {"HOME", "RADIO_PLAYING", "USB_AUDIO_PLAYING", "USB_VIDEO_PLAYING",
                "BLUETOOTH_PLAYING", "BLUETOOTH_NO_MEDIA", "PERMISSION_STATE",
                "USB_NO_DEVICE", "USB_DETECTED", "USB_SCANNING", "USB_REMOVED",
                "RADIO_ERROR"};
        for (int i = 0; i < states.length; i++) {
            if (states[i].equals(value)) { showPreview(i); return; }
        }
    }
    public void exit() {
        if (!active) return;
        active = false;
        listener.onDemoActiveChanged(false);
    }

    private void showMenu() {
        String[] labels = {"Home", "Radio playing", "USB audio playing", "USB video playing",
                "Bluetooth playing", "Bluetooth connected — no media", "Permission state",
                "Exit demo", "Use dark theme", "Use light theme", "Follow system theme"};
        new AlertDialog.Builder(activity).setTitle(R.string.debug_preview).setItems(labels,
                (dialog, index) -> {
                    if (index <= 6) showPreview(index);
                    else if (index == 7) exit();
                    else AppCompatDelegate.setDefaultNightMode(index == 8
                            ? AppCompatDelegate.MODE_NIGHT_YES : index == 9
                            ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                }).show();
    }

    private void showPreview(int index) {
        active = true;
        listener.onDemoActiveChanged(true);
        renderer.renderPreview(createPreview(index));
    }

    private PreviewUiState createPreview(int index) {
        if (index == 0) return state(MediaSourceType.HOME, "HOME · DEMO", "DEBUG DEMO",
                "Your media. One cockpit.", "Preview data — not live hardware.", VisualizerMode.IDLE,
                false, "", "", "", "", "", R.drawable.ic_music, "DEMO PREVIEW",
                "Choose a source", "Debug-only rendering of the source home.");
        if (index == 5) return state(MediaSourceType.BLUETOOTH, "BLUETOOTH · DEMO",
                "DEBUG DEMO · CONNECTED", "Preview audio device", "Bluetooth connected — No media",
                VisualizerMode.BLUETOOTH_CONNECTED, false, "", "", "", "", "",
                R.drawable.ic_bluetooth, "DEMO CONNECTED", "Preview audio device",
                "No media is playing. This is not a reported hardware state.");
        if (index == 6) return state(MediaSourceType.LIBRARY, "USB · DEMO",
                "DEBUG DEMO", "Media access required", "Permission explanation preview.",
                VisualizerMode.USB_NO_DEVICE, false, "", "", "", "", "", R.drawable.ic_library,
                "PERMISSION REQUIRED", "Reconnect USB",
                "Allow media access, or select the USB folder through Android.");
        if (index == 7) return state(MediaSourceType.LIBRARY, "USB · NO DEVICE · DEMO",
                "DEBUG DEMO · USB", "No USB detected", "Connect a mounted USB/OTG drive.",
                VisualizerMode.USB_NO_DEVICE, false, "", "", "", "", "",
                R.drawable.ic_library, "NO USB", "Connect USB OTG drive",
                "Android currently exposes no mounted removable volume.");
        if (index == 8) return state(MediaSourceType.LIBRARY, "USB · DETECTED · DEMO",
                "DEBUG DEMO · USB", "Mounted USB preview", "Volume name is simulated for layout review.",
                VisualizerMode.USB_INSERTED, false, "", "", "", "", "",
                R.drawable.ic_library, "USB DETECTED", "Mounted USB preview",
                "Debug rendering only — not reported hardware.");
        if (index == 9) return state(MediaSourceType.LIBRARY, "USB · SCANNING · DEMO",
                "DEBUG DEMO · USB", "Scanning mounted media", "Reading audio and video metadata…",
                VisualizerMode.USB_SCANNING, false, "", "", "", "", "",
                R.drawable.ic_library, "SCANNING", "Mounted USB preview",
                "Debug rendering only — no production storage is implied.");
        if (index == 10) return state(MediaSourceType.LIBRARY, "USB · REMOVED · DEMO",
                "DEBUG DEMO · USB", "USB removed", "Playback and stale media were cleared.",
                VisualizerMode.USB_REMOVED, false, "", "", "", "", "",
                R.drawable.ic_library, "DISCONNECTED", "USB removed",
                "Debug rendering only — not a reported hardware event.");
        if (index == 11) return state(MediaSourceType.RADIO, "RADIO · ERROR · DEMO",
                "DEBUG DEMO · STREAM ERROR", "Station unavailable",
                "The stream did not begin within the connection window.",
                VisualizerMode.ERROR, false, "", "", "", "", "",
                R.drawable.ic_radio, "STREAM UNAVAILABLE", "Unable to play this station",
                "Retry, choose Next, or hide the broken station locally.");
        boolean radio = index == 1;
        boolean audio = index == 2;
        boolean video = index == 3;
        boolean bluetooth = index == 4;
        MediaSourceType source = radio ? MediaSourceType.RADIO
                : bluetooth ? MediaSourceType.BLUETOOTH : MediaSourceType.LIBRARY;
        String badge = radio ? "RADIO" : bluetooth ? "BLUETOOTH" : video ? "VIDEO" : "AUDIO";
        String title = radio ? "Preview Internet Station" : video
                ? "HyperNova Motion Study" : "Aurora Test Tone";
        String subtitle = radio ? "Internet stream preview" : bluetooth
                ? "Preview artist · Preview audio device" : video
                ? "Local video · Preview" : "Preview artist · Local audio";
        VisualizerMode mode = radio ? VisualizerMode.RADIO_PLAYING
                : bluetooth ? VisualizerMode.BLUETOOTH_PLAYING : video
                ? VisualizerMode.LIBRARY_VIDEO_PLAYING : VisualizerMode.LIBRARY_AUDIO_PLAYING;
        return state(source, badge + " · DEMO", "DEBUG DEMO · PLAYING", title,
                subtitle, mode, true, badge, title, subtitle, radio ? "LIVE" : "1:31",
                radio ? "LIVE" : video ? "4:05" : "3:58", R.drawable.ic_music, "", "", "");
    }

    private PreviewUiState state(MediaSourceType source, String header, String heroEyebrow,
            String heroTitle, String heroSubtitle, VisualizerMode mode, boolean player,
            String mediaBadge, String mediaTitle, String mediaSubtitle, String elapsed,
            String duration, int icon, String stateEyebrow, String stateTitle, String stateMessage) {
        return new PreviewUiState(source, header, heroEyebrow, heroTitle, heroSubtitle, mode,
                player, mediaBadge, mediaTitle, mediaSubtitle, elapsed, duration, icon,
                stateEyebrow, stateTitle, stateMessage);
    }
}
