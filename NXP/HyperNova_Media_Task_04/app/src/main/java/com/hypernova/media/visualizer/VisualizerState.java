package com.hypernova.media.visualizer;

public final class VisualizerState {
    public final VisualizerMode mode;
    public final float progress;
    public final boolean animated;

    public VisualizerState(VisualizerMode mode, float progress, boolean animated) {
        this.mode = mode;
        this.progress = Math.max(0f, Math.min(1f, progress));
        this.animated = animated;
    }
}
