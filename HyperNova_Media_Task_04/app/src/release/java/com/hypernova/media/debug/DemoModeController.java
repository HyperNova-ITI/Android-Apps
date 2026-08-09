package com.hypernova.media.debug;

import android.content.Intent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.hypernova.media.ui.MainUiRenderer;

/** Release no-op. No demo metadata or preview entry point is packaged in release builds. */
public final class DemoModeController {
    public interface Listener { void onDemoActiveChanged(boolean active); }
    public DemoModeController(AppCompatActivity activity, View root, MainUiRenderer renderer,
            Listener listener) {}
    public boolean isActive() { return false; }
    public void applyIntent(Intent intent) {}
    public void exit() {}
}
