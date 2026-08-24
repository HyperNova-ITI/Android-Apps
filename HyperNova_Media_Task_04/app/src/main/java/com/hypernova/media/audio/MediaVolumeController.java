package com.hypernova.media.audio;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

/**
 * Controls the Android media-output volume used by HyperNova Media.
 *
 * This controller intentionally does not depend on HyperNova Settings,
 * CarSettings, or any HyperNova-specific settings application.
 *
 * It uses the standard Android STREAM_MUSIC output exposed by the NXP
 * Android guest.
 */
public final class MediaVolumeController {
    private static final String TAG = "HyperNovaVolume";

    private final AudioManager audioManager;

    private int minimum;
    private int maximum;
    private int current;
    private boolean available;

    public MediaVolumeController(Context context) {
        audioManager =
                context.getApplicationContext()
                        .getSystemService(AudioManager.class);

        refresh();
    }

    public synchronized void refresh() {
        available = false;
        minimum = 0;
        maximum = 0;
        current = 0;

        if (audioManager == null) {
            Log.e(TAG, "AudioManager unavailable");
            return;
        }

        try {
            minimum =
                    audioManager.getStreamMinVolume(
                            AudioManager.STREAM_MUSIC);

            maximum =
                    audioManager.getStreamMaxVolume(
                            AudioManager.STREAM_MUSIC);

            current =
                    audioManager.getStreamVolume(
                            AudioManager.STREAM_MUSIC);

            if (maximum <= minimum) {
                Log.w(
                        TAG,
                        "Invalid STREAM_MUSIC range: "
                                + minimum
                                + ".."
                                + maximum);
                return;
            }

            current = clamp(current, minimum, maximum);
            available = true;

        } catch (RuntimeException error) {
            Log.e(
                    TAG,
                    "Unable to read STREAM_MUSIC volume",
                    error);
        }
    }

    public synchronized boolean isAvailable() {
        return available;
    }

    public synchronized int getPercentage() {
        if (!available || maximum <= minimum) {
            return 0;
        }

        return Math.round(
                (current - minimum)
                        * 100f
                        / (maximum - minimum));
    }

    public synchronized boolean setPercentage(int requestedPercentage) {
        if (audioManager == null) {
            return false;
        }

        int percentage =
                clamp(requestedPercentage, 0, 100);

        refresh();

        if (!available) {
            return false;
        }

        int value =
                minimum
                        + Math.round(
                                percentage
                                        * (maximum - minimum)
                                        / 100f);

        value = clamp(value, minimum, maximum);

        try {
            audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    value,
                    0);

            current = value;

            Log.i(
                    TAG,
                    "Media volume set: "
                            + value
                            + "/"
                            + maximum
                            + " ("
                            + percentage
                            + "%)");

            return true;

        } catch (RuntimeException error) {
            Log.e(
                    TAG,
                    "Unable to set STREAM_MUSIC volume",
                    error);

            refresh();
            return false;
        }
    }

    private static int clamp(
            int value,
            int minimum,
            int maximum) {

        return Math.max(
                minimum,
                Math.min(value, maximum));
    }
}
